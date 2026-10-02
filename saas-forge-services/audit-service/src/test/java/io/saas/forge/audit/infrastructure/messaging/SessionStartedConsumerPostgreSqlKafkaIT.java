package io.saas.forge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.saas.forge.audit.application.AuditConsumerFailurePolicy;
import io.saas.forge.audit.application.AuditConsumerIsolation;
import io.saas.forge.audit.application.AuditConsumerIsolationService;
import io.saas.forge.audit.application.AuditRecordService;
import io.saas.forge.audit.infrastructure.persistence.JdbcAuditConsumerIsolationRepository;
import io.saas.forge.audit.infrastructure.persistence.JdbcAuditRecordRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class SessionStartedConsumerPostgreSqlKafkaIT {
    private static final String TOPIC = "saas.forge.test.iam-service.events";
    private static final String TENANT_TOPIC = "saas.forge.test.tenant-access-service.events";
    private static final String IAM_ISOLATION_TOPIC =
            "saas.forge.test.audit-service.iam-session-isolations";
    private static final String APP_PASSWORD = "audit-app-test-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    private static JdbcTemplate admin;
    private static JdbcTemplate app;
    private static IamSessionKafkaConsumer listener;
    private static TenantAccessKafkaConsumer tenantListener;
    private static ExampleFactKafkaConsumer exampleListener;
    private static SimpleMeterRegistry meters;
    private static AuditConsumerIsolationService isolations;
    private static AuditIsolationPublisher isolationPublisher;
    private static DefaultKafkaProducerFactory<String, String> isolationProducerFactory;

    @BeforeAll
    static void setUp() {
        admin = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE ROLE audit_app LOGIN PASSWORD '" + APP_PASSWORD + "'");
        admin.execute("GRANT CONNECT ON DATABASE " + POSTGRES.getDatabaseName() + " TO audit_app");
        admin.execute("GRANT USAGE ON SCHEMA public TO audit_app");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var appDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "audit_app", APP_PASSWORD);
        app = new JdbcTemplate(appDataSource);
        var serviceTarget = new AuditRecordService(
                new JdbcAuditRecordRepository(app),
                Clock.fixed(Instant.parse("2026-08-28T10:16:00Z"), ZoneOffset.UTC));
        ProxyFactory serviceProxy = new ProxyFactory(serviceTarget);
        serviceProxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(appDataSource), new AnnotationTransactionAttributeSource()));
        var service = (AuditRecordService) serviceProxy.getProxy();
        exampleListener = new ExampleFactKafkaConsumer(new ExampleFactEventValidator(new ObjectMapper(),
                ExampleFactEventValidatorTest.TOPIC), service, io.opentelemetry.api.OpenTelemetry.noop());
        var isolationTarget = new AuditConsumerIsolationService(
                new JdbcAuditConsumerIsolationRepository(app),
                Clock.fixed(Instant.parse("2026-08-28T10:16:00Z"), ZoneOffset.UTC));
        ProxyFactory isolationProxy = new ProxyFactory(isolationTarget);
        isolationProxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(appDataSource), new AnnotationTransactionAttributeSource()));
        isolations = (AuditConsumerIsolationService) isolationProxy.getProxy();
        isolationProducerFactory = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"));
        isolationPublisher = new AuditIsolationPublisher(
                isolations, new KafkaTemplate<>(isolationProducerFactory),
                new AuditConsumerFailurePolicy(10, Duration.ofSeconds(1), Duration.ofMinutes(1)),
                Clock.fixed(Instant.parse("2026-08-28T10:16:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(30));
        ObjectMapper objectMapper = new ObjectMapper();
        var sessionStarted = new SessionStartedEventValidator(objectMapper, TOPIC);
        var tenantContextSwitched = new TenantContextSwitchedEventValidator(objectMapper, TOPIC);
        listener = new IamSessionKafkaConsumer(
                new IamSessionEventValidator(objectMapper, sessionStarted, tenantContextSwitched), service);
        var tenantCreated = new TenantCreatedEventValidator(objectMapper, TENANT_TOPIC);
        meters = new SimpleMeterRegistry();
        tenantListener = new TenantAccessKafkaConsumer(
                new TenantAccessEventValidator(objectMapper, tenantCreated, TENANT_TOPIC), service, meters);
    }

    @AfterEach
    void removeFailureTrigger() {
        admin.execute("DROP TRIGGER IF EXISTS fail_audit_record_insert ON audit_records");
        admin.execute("DROP FUNCTION IF EXISTS fail_audit_record_insert()");
        admin.execute("DROP TRIGGER IF EXISTS fail_tenant_audit_record_insert ON audit_records");
        admin.execute("DROP FUNCTION IF EXISTS fail_tenant_audit_record_insert()");
    }

    @AfterAll
    static void closeIsolationProducer() {
        isolationProducerFactory.destroy();
    }

    @Test
    void exampleFactsRemainAppendOnlyAndRedeliveryAfterCommitIsDeduplicated() {
        String eventId = uuidV7(100);
        String group = "example-redelivery-" + UUID.randomUUID();
        try (var first = consumer(ExampleFactEventValidatorTest.TOPIC, group)) {
            seekToEnd(first);
            send(ExampleFactEventValidatorTest.TOPIC, ExampleFactEventValidatorTest.PROJECT,
                    ExampleFactEventValidatorTest.event("task", "created", eventId));
            var message = pollOne(first);
            assertThrows(RuntimeException.class, () -> exampleListener.consume(message,
                    () -> { throw new RuntimeException("simulated crash after commit"); }));
        }
        try (var replay = consumer(ExampleFactEventValidatorTest.TOPIC, group)) {
            exampleListener.consume(pollOne(replay), replay::commitSync);
        }
        assertEquals(1, count("audit_records", eventId));
        assertEquals(1, count("audit_consumed_events", eventId));
        assertEquals(ExampleFactEventValidatorTest.TRACE, app.queryForObject(
                "SELECT trace_id FROM audit_records WHERE source_event_id=?::uuid", String.class, eventId));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> app.update(
                "UPDATE audit_records SET action='TASK_DELETED' WHERE source_event_id=?::uuid", eventId));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> app.update(
                "DELETE FROM audit_records WHERE source_event_id=?::uuid", eventId));
    }

    @Test
    void writesOnceAndSafelyAbsorbsRedeliveryAfterCommitBeforeAcknowledgmentFailure() {
        String eventId = uuidV7(11);
        String payload = event(eventId);
        String group = "audit-redelivery-" + UUID.randomUUID();

        try (KafkaConsumer<String, String> first = consumer(group)) {
            seekToEnd(first);
            send(identityId(), payload);
            ConsumerRecord<String, String> message = pollOne(first);
            assertThrows(RuntimeException.class,
                    () -> listener.consume(message, () -> { throw new RuntimeException("simulated crash"); }));
        }

        try (KafkaConsumer<String, String> redelivery = consumer(group)) {
            ConsumerRecord<String, String> message = pollOne(redelivery);
            listener.consume(message, redelivery::commitSync);
        }

        assertEquals(1, count("audit_records", eventId));
        assertEquals(1, count("audit_consumed_events", eventId));
        assertEquals("{\"purpose\": \"USER_TENANT\", \"contextType\": \"TENANT\", "
                        + "\"sessionOutcome\": \"ACCESS_TOKEN_ISSUED\"}",
                app.queryForObject(
                        "SELECT metadata::text FROM audit_records WHERE source_event_id = ?::uuid",
                        String.class, eventId));
        assertEquals(0, app.queryForObject(
                "SELECT count(*) FROM audit_records WHERE source_event_id = ?::uuid AND tenant_id IS NOT NULL",
                Integer.class, eventId));
    }

    @Test
    void writesTenantContextSwitchOnceWithWhitelistedMapping() {
        String eventId = uuidV7(13);
        String group = "audit-context-switch-" + UUID.randomUUID();

        try (KafkaConsumer<String, String> kafka = consumer(group)) {
            seekToEnd(kafka);
            send(identityId(), TenantContextSwitchedEventValidatorTest.event(eventId, ""));
            ConsumerRecord<String, String> message = pollOne(kafka);
            listener.consume(message, kafka::commitSync);
            listener.consume(message, () -> {});
        }

        assertEquals(1, count("audit_records", eventId));
        assertEquals(1, count("audit_consumed_events", eventId));
        Map<String, Object> record = app.queryForMap("""
                SELECT actor_identity_id, tenant_id, action, resource_type, resource_id, result,
                       metadata ->> 'previousMembershipId' AS previous_membership_id,
                       metadata ->> 'targetMembershipId' AS target_membership_id
                FROM audit_records WHERE source_event_id = ?::uuid
                """, eventId);
        assertEquals(identityId(), record.get("actor_identity_id").toString());
        assertEquals(TenantContextSwitchedEventValidatorTest.tenantId(), record.get("tenant_id").toString());
        assertEquals("TENANT_CONTEXT_SWITCHED", record.get("action"));
        assertEquals("REFRESH_TOKEN_FAMILY", record.get("resource_type"));
        assertEquals(TenantContextSwitchedEventValidatorTest.familyId(), record.get("resource_id").toString());
        assertEquals("SUCCESS", record.get("result"));
        assertEquals("019535d9-0001-7000-8000-000000000004", record.get("previous_membership_id"));
        assertEquals("019535d9-0001-7000-8000-000000000005", record.get("target_membership_id"));
    }

    @Test
    void rollsBackDeduplicationWhenAuditRecordInsertFailsAndDoesNotAcknowledge() {
        String eventId = uuidV7(12);
        admin.execute("""
                CREATE FUNCTION fail_audit_record_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced audit insert failure'; END
                $$
                """);
        admin.execute("""
                CREATE TRIGGER fail_audit_record_insert BEFORE INSERT ON audit_records
                FOR EACH STATEMENT EXECUTE FUNCTION fail_audit_record_insert()
                """);
        AtomicInteger acknowledgments = new AtomicInteger();
        Acknowledgment acknowledgment = acknowledgments::incrementAndGet;

        assertThrows(DataAccessException.class, () -> listener.consume(
                new ConsumerRecord<>(TOPIC, 0, 0, identityId(), event(eventId)), acknowledgment));

        assertEquals(0, acknowledgments.get());
        assertEquals(0, count("audit_records", eventId));
        assertEquals(0, count("audit_consumed_events", eventId));
    }

    @Test
    void keepsBothConsumerIdentitiesIndependentAcrossSuccessDuplicateAndRecovery() {
        String eventId = uuidV7(14);
        String iamGroup = "audit-iam-independent-" + UUID.randomUUID();
        String tenantGroup = "audit-tenant-independent-" + UUID.randomUUID();

        try (KafkaConsumer<String, String> iam = consumer(TOPIC, iamGroup);
                KafkaConsumer<String, String> tenant = consumer(TENANT_TOPIC, tenantGroup)) {
            seekToEnd(iam);
            seekToEnd(tenant);
            send(TOPIC, identityId(), event(eventId));
            send(TENANT_TOPIC, TenantCreatedEventValidatorTest.tenantId(),
                    TenantCreatedEventValidatorTest.event(eventId, ""));
            ConsumerRecord<String, String> iamMessage = pollOne(iam);
            ConsumerRecord<String, String> tenantMessage = pollOne(tenant);

            listener.consume(iamMessage, iam::commitSync);
            listener.consume(iamMessage, () -> {});

            admin.execute("""
                    CREATE FUNCTION fail_tenant_audit_record_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.source_type = 'com.saas.forge.tenant.created.v1' THEN
                            RAISE EXCEPTION 'forced tenant audit insert failure';
                        END IF;
                        RETURN NEW;
                    END
                    $$
                    """);
            admin.execute("""
                    CREATE TRIGGER fail_tenant_audit_record_insert BEFORE INSERT ON audit_records
                    FOR EACH ROW EXECUTE FUNCTION fail_tenant_audit_record_insert()
                    """);
            assertThrows(DataAccessException.class,
                    () -> tenantListener.consume(tenantMessage, tenant::commitSync));
        }

        assertEquals(1, app.queryForObject("""
                SELECT count(*) FROM audit_records
                WHERE source_event_id = ?::uuid AND source = 'urn:saas.forge:iam-service'
                """, Integer.class, eventId));
        assertEquals(0, app.queryForObject("""
                SELECT count(*) FROM audit_consumed_events
                WHERE event_id = ?::uuid AND consumer_name = ?
                """, Integer.class, eventId, TenantCreatedEventValidator.CONSUMER_NAME));

        admin.execute("DROP TRIGGER fail_tenant_audit_record_insert ON audit_records");
        admin.execute("DROP FUNCTION fail_tenant_audit_record_insert()");
        try (KafkaConsumer<String, String> tenant = consumer(TENANT_TOPIC, tenantGroup)) {
            ConsumerRecord<String, String> redelivery = pollOne(tenant);
            tenantListener.consume(redelivery, tenant::commitSync);
            tenantListener.consume(redelivery, () -> {});
        }

        assertEquals(2, app.queryForObject(
                "SELECT count(*) FROM audit_records WHERE source_event_id = ?::uuid",
                Integer.class, eventId));
        assertEquals(2, app.queryForObject(
                "SELECT count(*) FROM audit_consumed_events WHERE event_id = ?::uuid",
                Integer.class, eventId));
        assertEquals(1, app.queryForObject("""
                SELECT count(*) FROM audit_consumed_events
                WHERE event_id = ?::uuid AND consumer_name = ?
                """, Integer.class, eventId, SessionStartedEventValidator.CONSUMER_NAME));
        assertEquals(1, app.queryForObject("""
                SELECT count(*) FROM audit_consumed_events
                WHERE event_id = ?::uuid AND consumer_name = ?
                """, Integer.class, eventId, TenantCreatedEventValidator.CONSUMER_NAME));
        Map<String, Object> tenantRecord = app.queryForMap("""
                SELECT actor_identity_id, tenant_id, action, resource_type, resource_id, result,
                       metadata ->> 'initialStatus' AS initial_status
                FROM audit_records
                WHERE source_event_id = ?::uuid AND source = 'urn:saas.forge:tenant-access-service'
                """, eventId);
        assertEquals(TenantCreatedEventValidatorTest.actorIdentityId(),
                tenantRecord.get("actor_identity_id").toString());
        assertEquals(TenantCreatedEventValidatorTest.tenantId(), tenantRecord.get("tenant_id").toString());
        assertEquals("TENANT_CREATED", tenantRecord.get("action"));
        assertEquals("TENANT", tenantRecord.get("resource_type"));
        assertEquals(TenantCreatedEventValidatorTest.tenantId(), tenantRecord.get("resource_id").toString());
        assertEquals("SUCCESS", tenantRecord.get("result"));
        assertEquals("PENDING", tenantRecord.get("initial_status"));
    }

    @Test
    void acknowledgesTenantEventRegisteredToOtherConsumerWithoutDatabaseState() {
        String eventId = uuidV7(8);
        String group = "audit-tenant-ignored-" + UUID.randomUUID();

        try (KafkaConsumer<String, String> tenant = consumer(TENANT_TOPIC, group)) {
            seekToEnd(tenant);
            send(TENANT_TOPIC, TenantCreatedEventValidatorTest.tenantId(),
                    TenantAccessEventValidatorTest.registeredTenantSuspendedEvent());
            tenantListener.consume(pollOne(tenant), tenant::commitSync);
        }

        assertEquals(0, count("audit_records", eventId));
        assertEquals(0, count("audit_consumed_events", eventId));
        assertEquals(1.0, meters.get("saas.forge.audit.consumer.events")
                .tag("consumer", TenantCreatedEventValidator.CONSUMER_NAME)
                .tag("result", "ignored")
                .counter().count());
    }

    @Test
    void runtimeRoleCanOnlySelectAndInsertAppendOnlyTables() {
        assertThrows(DataAccessException.class, () -> app.update("UPDATE audit_records SET result = result"));
        assertThrows(DataAccessException.class, () -> app.update("DELETE FROM audit_records"));
        assertThrows(DataAccessException.class, () -> app.execute("TRUNCATE audit_records"));
        assertEquals(0, admin.queryForObject(
                "SELECT count(*) FROM pg_policies WHERE tablename = 'audit_records'", Integer.class));
        assertEquals(Boolean.FALSE, admin.queryForObject(
                "SELECT relrowsecurity FROM pg_class WHERE relname = 'audit_records'", Boolean.class));
    }

    @Test
    void publishesClaimedSafeIsolationToItsConsumerTopicWithTheOriginalEventId() {
        String eventId = uuidV7(83);
        String snapshot = event(eventId);
        String group = "audit-isolation-publisher-" + UUID.randomUUID();

        try (KafkaConsumer<String, String> isolationConsumer = consumer(IAM_ISOLATION_TOPIC, group)) {
            seekToEnd(isolationConsumer);
            var isolationId = isolations.isolate(new AuditConsumerIsolation(
                    SessionStartedEventValidator.CONSUMER_NAME, TOPIC, 0, 83, identityId(),
                    UUID.fromString(eventId), SessionStartedEventValidator.SOURCE,
                    SessionStartedEventValidator.EVENT_TYPE, "a".repeat(64), "RETRY_EXHAUSTED",
                    "DataAccessResourceFailureException", 10, snapshot, IAM_ISOLATION_TOPIC));

            isolationPublisher.publishNext();
            ConsumerRecord<String, String> published = pollOne(isolationConsumer);

            assertEquals(identityId(), published.key());
            assertEquals(snapshot, published.value());
            assertEquals(eventId, new ObjectMapper().readTree(published.value()).get("id").asText());
            assertEquals(1, app.queryForObject("""
                    SELECT count(*) FROM audit_isolation_deliveries
                    WHERE isolation_id = ? AND published_at IS NOT NULL
                    """, Integer.class, isolationId));
            assertEquals(1, app.queryForObject("""
                    SELECT count(*) FROM audit_isolation_attempts
                    WHERE isolation_id = ? AND action = 'ISOLATION_DELIVERED'
                    """, Integer.class, isolationId));
        }
    }

    private static void send(String key, String payload) {
        send(TOPIC, key, payload);
    }

    private static void send(String topic, String key, String payload) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"))) {
            try {
                producer.send(new ProducerRecord<>(topic, key, payload)).get();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static KafkaConsumer<String, String> consumer(String group) {
        return consumer(TOPIC, group);
    }

    private static KafkaConsumer<String, String> consumer(String topic, String group) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private static ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            var records = consumer.poll(Duration.ofMillis(250));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("未在期限内收到 Kafka 消息");
    }

    private static void seekToEnd(KafkaConsumer<String, String> consumer) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (consumer.assignment().isEmpty() && Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(250));
        }
        if (consumer.assignment().isEmpty()) {
            throw new AssertionError("未在期限内获得 Kafka 分区");
        }
        consumer.seekToEnd(consumer.assignment());
        consumer.assignment().forEach(consumer::position);
    }

    private static int count(String table, String eventId) {
        String eventColumn = table.equals("audit_records") ? "source_event_id" : "event_id";
        return app.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + eventColumn + " = ?::uuid",
                Integer.class, eventId);
    }

    private static String event(String eventId) {
        return SessionStartedEventValidatorTest.event("")
                .replace("019535d9-0001-7000-8000-000000000001", eventId);
    }

    private static String uuidV7(int suffix) {
        return "019535d9-0001-7000-8000-%012d".formatted(suffix);
    }

    private static String identityId() {
        return "019535d9-0001-7000-8000-000000000002";
    }
}
