package io.saas.forge.tenantaccess.application.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;


import io.saas.forge.tenantaccess.application.administrator.IdentityCredentialDisposition;
import io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupRepository;
import io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupWorkflow;
import io.saas.forge.tenantaccess.application.administrator.IdentityProvisioningGateway;
import io.saas.forge.tenantaccess.application.administrator.InitializationRecoveryPolicy;
import io.saas.forge.tenantaccess.application.administrator.InitializationWorkflow;
import io.saas.forge.tenantaccess.application.administrator.InitializationWorkflowState;
import io.saas.forge.tenantaccess.application.administrator.InitializationQuotaGateway;
import io.saas.forge.tenantaccess.application.administrator.InitializeTenantAdministratorService;
import io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saas.forge.tenantaccess.application.administrator.TenantAdministratorInitializationResult;
import io.saas.forge.tenantaccess.application.administrator.TenantAdministratorInitializedEventFactory;
import io.saas.forge.tenantaccess.application.administrator.TenantAdministratorInitializationRepository;
import io.saas.forge.tenantaccess.application.administrator.TenantAdministratorInitializationException;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saas.forge.tenantaccess.domain.tenant.TenantStatus;
import io.saas.forge.tenantaccess.infrastructure.messaging.TenantAccessOutboxPublisher;
import io.saas.forge.tenantaccess.infrastructure.persistence.MyBatisTenantAccessOutboxEventRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.MyBatisAdministratorPasswordSetupRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.MyBatisTenantAdministratorInitializationRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.MyBatisTenantCreationIdempotency;
import io.saas.forge.tenantaccess.infrastructure.persistence.MyBatisTenantRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.MyBatisTenantLifecycleRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringJUnitConfig(TenantCreationPostgreSqlIT.PersistenceConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantCreationPostgreSqlIT {
    private static final Path REPOSITORY_ROOT = repositoryRoot();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
            .withDatabaseName("saas.forge")
            .withUsername("saas.forge_admin")
            .withPassword("admin-password")
            .withEnv("IAM_MIGRATOR_PASSWORD", "iam-migrator-password")
            .withEnv("IAM_APP_PASSWORD", "iam-app-password")
            .withEnv("TENANT_ACCESS_MIGRATOR_PASSWORD", "tenant-access-migrator-password")
            .withEnv("TENANT_ACCESS_APP_PASSWORD", "tenant-access-app-password")
            .withEnv("ENTITLEMENT_MIGRATOR_PASSWORD", "entitlement-migrator-password")
            .withEnv("ENTITLEMENT_APP_PASSWORD", "entitlement-app-password")
            .withEnv("AUDIT_MIGRATOR_PASSWORD", "audit-migrator-password")
            .withEnv("AUDIT_APP_PASSWORD", "audit-app-password")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(REPOSITORY_ROOT.resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Autowired
    private CreatePendingTenantService service;

    @Autowired
    private TenantQueryService tenantQueries;

    @Autowired
    private InitialSubscriptionEligibilityService eligibility;

    @Autowired
    private TenantAdministratorInitializationRepository administratorInitialization;

    @Autowired
    private AdministratorPasswordSetupRepository administratorPasswordSetups;

    @Autowired
    private TenantAccessOutboxPublisher outboxPublisher;

    @Autowired
    private TenantLifecycleService tenantLifecycle;

    @Autowired
    private TenantLifecycleRepository tenantLifecycleWorkflows;

    @Autowired
    private ScriptedSessionRevocationGateway sessionRevocations;

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(jdbcUrl(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void clean() throws SQLException {
        executeAsMigrator("TRUNCATE tenant_creation_recovery, tenant_access_outbox_events, tenant_creation_idempotency, memberships, tenants CASCADE");
        sessionRevocations.reset();
    }

    @Autowired
    private RecoverableTenantCreationService recoverableCreation;

    @Autowired
    private TenantCreationRecoveryRepository creationRecoveryRepository;

    @Autowired
    private io.saas.forge.tenantaccess.application.administrator.AdministratorInitializationQueries initializationQueries;

    @Autowired
    private io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupQueries notificationQueries;

    @Test
    void readsPasswordNotificationIndependentlyBeforeInitialization() throws Exception {
        UUID actor = uuidV7(880);
        var tenant = service.create(actor, uuidV7(881), "Notification status", null, null);
        var mvc = standaloneSetup(new io.saas.forge.tenantaccess.api.TenantCreationController(
                authorization -> actor, recoverableCreation, null, null, null, tenantQueries, null,
                new io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupQueryService(notificationQueries,
                        new io.saas.forge.tenantaccess.application.administrator.PasswordSetupDeliveryGateway() {
                            public void deliver(UUID requestId, UUID identityId) { throw new AssertionError("Read must not send"); }
                            public NotificationState notification(UUID requestId, UUID identityId) { return NotificationState.PENDING; }
                        }, null, Clock.systemUTC())))
                .setControllerAdvice(new io.saas.forge.tenantaccess.api.TenantCreationExceptionHandler()).build();
        mvc.perform(get("/api/v1/platform/tenants/" + tenant.id() + "/administrator-password-setup"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.canResend").value(false))
                .andExpect(jsonPath("$.canContinue").value(false))
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist());
    }

    @Test
    void activatedTenantNotificationRemainsIndependentWhenMailIsPending() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID actor = uuidV7(882);
        UUID identity = uuidV7(883);
        var tenant = service.create(actor, uuidV7(884), "Pending notification", null, null);
        var prepared = administratorInitialization.prepare(workflow(tenant.id(), actor, uuidV7(885), "b".repeat(64)), now);
        var claimed = administratorInitialization.claim(prepared.workflowId(), "test", now, now.plusSeconds(30)).orElseThrow();
        var ready = administratorInitialization.completeIdentity(claimed, identity, IdentityCredentialDisposition.SETUP_ALLOWED, now);
        var quota = administratorInitialization.completeQuotaConsumption(ready, now);
        var activating = administratorInitialization.beginActivation(quota, now);
        administratorInitialization.activate(activating, identity, IdentityCredentialDisposition.SETUP_ALLOWED, now);
        var delivered = new java.util.concurrent.atomic.AtomicBoolean();
        var requestIds = new java.util.ArrayList<UUID>();
        var gateway = new io.saas.forge.tenantaccess.application.administrator.PasswordSetupDeliveryGateway() {
            public void deliver(UUID requestId, UUID identityId) {
                requestIds.add(requestId);
                if (!delivered.get()) throw new io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException(new IllegalStateException("mail unavailable"));
            }
            public NotificationState notification(UUID requestId, UUID identityId) {
                return delivered.get() ? NotificationState.MAIL_SERVICE_ACCEPTED : NotificationState.PENDING;
            }
        };
        var clock = Clock.fixed(now.plusSeconds(31), java.time.ZoneOffset.UTC);
        var resends = new io.saas.forge.tenantaccess.application.administrator.ResendAdministratorPasswordSetupService(
                administratorPasswordSetups, gateway, new UuidV7Generator(clock, new java.security.SecureRandom()), clock,
                new io.saas.forge.tenantaccess.application.administrator.InitializationRecoveryPolicy(
                        java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), 1), "notification-test");
        var query = new io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupQueryService(
                notificationQueries, gateway, resends, clock);
        assertEquals("PENDING", query.get(actor, tenant.id()).state().name());
        assertEquals("ACTIVE", tenantQueries.get(tenant.id()).status().name());
        var membership = initializationQueries.get(tenant.id()).initialMembershipId();
        assertThrows(io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupException.class,
                () -> resends.resend(actor, uuidV7(886), tenant.id(), null));
        // 另一管理员可从旧页面并发提交；它不能遮蔽原操作者的未决记录。
        administratorPasswordSetups.prepare(passwordSetupWorkflow(tenant.id(), uuidV7(888), uuidV7(889),
                uuidV7(878), uuidV7(879), now.plusSeconds(32)), now.plusSeconds(32));
        var progress = query.get(actor, tenant.id());
        assertEquals("UNKNOWN", query.get(actor, tenant.id(), uuidV7(877), null).operationState().name());
        assertFalse(query.get(actor, tenant.id(), uuidV7(877), null).canResend());
        assertEquals("PENDING", progress.operationState().name());
        assertEquals("PENDING", progress.state().name());
        assertTrue(progress.canContinue());
        assertFalse(progress.canResend());
        assertFalse(query.get(uuidV7(887), tenant.id()).canContinue());
        assertEquals(null, query.get(uuidV7(887), tenant.id()).resendId());
        var denied = assertThrows(io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupException.class,
                () -> query.recover(uuidV7(887), tenant.id(), progress.resendId(), null));
        assertEquals("PASSWORD_SETUP_RESEND_NOT_FOUND", denied.code());
        var currentActor = new java.util.concurrent.atomic.AtomicReference<UUID>(actor);
        var mvc = standaloneSetup(new io.saas.forge.tenantaccess.api.TenantCreationController(
                authorization -> {
                    if (currentActor.get() == null) throw new io.saas.forge.sdk.auth.PlatformAuthorizationDeniedException();
                    return currentActor.get();
                }, recoverableCreation, null, resends, null, tenantQueries, null, query))
                .setControllerAdvice(new io.saas.forge.tenantaccess.api.TenantCreationExceptionHandler()).build();
        String readPath = "/api/v1/platform/tenants/" + tenant.id() + "/administrator-password-setup";
        String recoveryPath = "/api/v1/platform/tenants/" + tenant.id() + "/administrator-password-setups/" + progress.resendId() + "/recovery";
        currentActor.set(uuidV7(887));
        mvc.perform(get(readPath)).andExpect(status().isOk()).andExpect(jsonPath("$.canContinue").value(false))
                .andExpect(jsonPath("$.resendId").doesNotExist());
        mvc.perform(post(recoveryPath).contentType("application/json").content("{}")).andExpect(status().isNotFound());
        currentActor.set(null);
        mvc.perform(get(readPath)).andExpect(status().isForbidden());
        mvc.perform(post(recoveryPath).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        currentActor.set(actor);
        delivered.set(true);
        mvc.perform(post(recoveryPath).contentType("application/json").content("{}")).andExpect(status().isNoContent());
        query.recover(actor, tenant.id(), progress.resendId(), null);
        assertEquals(2, requestIds.size());
        assertEquals(requestIds.get(0), requestIds.get(1));
        assertEquals("MAIL_SERVICE_ACCEPTED", query.get(actor, tenant.id()).state().name());
        assertEquals("ACTIVE", tenantQueries.get(tenant.id()).status().name());
        assertEquals(membership, initializationQueries.get(tenant.id()).initialMembershipId());
        assertEquals("COMPLETED", query.get(actor, tenant.id(), uuidV7(886), null).operationState().name());
        assertEquals("UNKNOWN", query.get(uuidV7(887), tenant.id(), uuidV7(886), null).operationState().name());
        assertFalse(query.get(actor, tenant.id()).canResend());
        var expired = new io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupQueryService(
                notificationQueries, gateway, resends, Clock.fixed(now.plusSeconds(31 + 86400), java.time.ZoneOffset.UTC));
        assertThrows(io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupException.class,
                () -> expired.recover(actor, tenant.id(), progress.resendId(), null));
        assertEquals(2, requestIds.size());
    }

    @Test
    void exposesInitializationBusinessProgressWithoutRecoveryMaterials() throws Exception {
        UUID actor = uuidV7(890);
        var tenant = service.create(actor, uuidV7(891), "Initialization progress", null, null);
        var mvc = standaloneSetup(
                new io.saas.forge.tenantaccess.api.TenantCreationController(
                        authorization -> actor, recoverableCreation, null, null, null, tenantQueries,
                        new io.saas.forge.tenantaccess.application.administrator.AdministratorInitializationQueryService(
                                initializationQueries, null, Clock.systemUTC())))
                .setControllerAdvice(new io.saas.forge.tenantaccess.api.TenantCreationExceptionHandler()).build();
        mvc.perform(get("/api/v1/platform/tenants/" + tenant.id() + "/administrator-initialization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("NOT_STARTED"))
                .andExpect(jsonPath("$.canStart").value(true))
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.administratorEmail").doesNotExist());
    }

    @Test
    void originalActorRecoversDurableInitializationAndReadsHistoricalMembershipAfterLostResponse() throws Exception {
        UUID actor = uuidV7(893);
        UUID key = uuidV7(894);
        var tenant = service.create(actor, uuidV7(895), "Durable initialization", null, null);
        var available = new java.util.concurrent.atomic.AtomicBoolean(false);
        var consumed = new java.util.concurrent.atomic.AtomicInteger();
        Instant started = Instant.now().minus(Duration.ofDays(2));
        Clock oldClock = Clock.fixed(started, java.time.ZoneOffset.UTC);
        var ids = new UuidV7Generator(oldClock, new SecureRandom());
        var initializer = new InitializeTenantAdministratorService(administratorInitialization,
                (request, email, name) -> {
                    if (!available.get()) throw new RemoteWorkflowUnavailableException(new IllegalStateException("Dependency unavailable"));
                    return new IdentityProvisioningGateway.Result(uuidV7(896), IdentityCredentialDisposition.SETUP_ALLOWED);
                },
                new InitializationQuotaGateway() {
                    public void consume(UUID tenantId, UUID operationId) { consumed.incrementAndGet(); }
                    public void release(UUID tenantId, UUID operationId) { consumed.decrementAndGet(); }
                },
                (request, identity) -> { throw new RemoteWorkflowUnavailableException(new IllegalStateException("Notification unavailable")); },
                ids, oldClock,
                new InitializationRecoveryPolicy(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1), 1),
                "http-recovery-it");
        assertThrows(RemoteWorkflowUnavailableException.class,
                () -> initializer.initialize(actor, key, tenant.id(), "owner@example.test", null, null));
        var query = new io.saas.forge.tenantaccess.application.administrator.AdministratorInitializationQueryService(
                initializationQueries, initializer, Clock.systemUTC());
        var currentActor = new java.util.concurrent.atomic.AtomicReference<>(actor);
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var mvc = standaloneSetup(new io.saas.forge.tenantaccess.api.TenantCreationController(
                authorization -> {
                    if (!allowed.get()) throw new io.saas.forge.sdk.auth.PlatformAuthorizationDeniedException();
                    return currentActor.get();
                }, recoverableCreation, initializer, null, null, tenantQueries, query))
                .setControllerAdvice(new io.saas.forge.tenantaccess.api.TenantCreationExceptionHandler()).build();
        String progressPath = "/api/v1/platform/tenants/" + tenant.id() + "/administrator-initialization";
        var progress = query.get(actor, tenant.id());
        String recoveryPath = "/api/v1/platform/tenants/" + tenant.id()
                + "/administrator-initializations/" + progress.initializationId() + "/recovery";
        mvc.perform(get(progressPath)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.canContinue").value(true))
                .andExpect(jsonPath("$.administratorEmail").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.leaseUntil").doesNotExist());
        currentActor.set(uuidV7(897));
        mvc.perform(get(progressPath)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.canContinue").value(false));
        mvc.perform(post(recoveryPath).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        currentActor.set(actor);
        allowed.set(false);
        mvc.perform(post(recoveryPath).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        allowed.set(true);
        available.set(true);
        mvc.perform(post(recoveryPath).contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post(recoveryPath).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(get(progressPath)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.initialAdministratorMembershipId").isString())
                .andExpect(jsonPath("$.canContinue").value(false));
        assertEquals(TenantStatus.ACTIVE, tenantQueries.get(tenant.id()).status());
        assertEquals(1, consumed.get());
        // 超过普通保留期的稳定结果仍指向原根，邮件失败不会逆转初始化。
        assertEquals(progress.initializationId(), query.get(actor, tenant.id()).initializationId());
    }

    @Test
    void exposesAuthoritativeTenantAndRecoveryThroughPublishedHttpOperations() throws Exception {
        UUID actor = uuidV7(870);
        UUID key = uuidV7(871);
        var mvc = standaloneSetup(
                new io.saas.forge.tenantaccess.api.TenantCreationController(
                        authorization -> actor, recoverableCreation, null, null, null, tenantQueries))
                .setControllerAdvice(new io.saas.forge.tenantaccess.api.TenantCreationExceptionHandler()).build();
        mvc.perform(post("/api/v1/platform/tenants")
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"displayName\":\"HTTP recovery\"}"))
                .andExpect(status().isCreated());
        var record = recoverableCreation.list(actor, null, 50).items().get(0);
        mvc.perform(get("/api/v1/platform/tenants")
                        .param("name", "HTTP recovery").param("status", "PENDING").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].id").value(record.tenantId().toString()))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/v1/platform/tenants/" + record.tenantId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/v1/platform/tenant-creations"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].id").value(record.id().toString()));
        mvc.perform(get("/api/v1/platform/tenant-creations/" + record.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMMITTED"));
        mvc.perform(post("/api/v1/platform/tenant-creations/" + record.id() + "/recovery")
                        .header("Idempotency-Key", key).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tenantId").value(record.tenantId().toString()));
        assertEquals(1, tenantQueries.list("HTTP recovery", null, null, 50).items().size());
    }

    @Test
    void reportsProcessingWhileAnotherTransactionOwnsTheCreation() throws Exception {
        UUID actor = uuidV7(860);
        UUID key = uuidV7(861);
        var saved = creationRecoveryRepository.prepare(actor, key, "Processing recovery", null, Instant.now());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> owner = executor.submit(() -> creationRecoveryRepository.locked(actor, saved.id(), entry -> {
                locked.countDown();
                try {
                    assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return null;
            }));
            assertTrue(locked.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var processing = recoverableCreation.get(actor, saved.id());
            assertEquals(TenantCreationRecovery.State.PROCESSING, processing.state());
            assertFalse(processing.canReplay());
            assertEquals("IDEMPOTENCY_REQUEST_IN_PROGRESS", assertThrows(TenantLifecycleException.class,
                    () -> recoverableCreation.recover(actor, saved.id(), key, null)).code());
            release.countDown();
            owner.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(TenantCreationRecovery.State.NOT_COMMITTED,
                    recoverableCreation.get(actor, saved.id()).state());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDuplicateCreatesHaveOneAuthoritativeTenant() throws Exception {
        UUID actor = uuidV7(850);
        UUID key = uuidV7(851);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<UUID>> attempts = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    try {
                        return recoverableCreation.create(actor, key, "Concurrent recovery", null, null).id();
                    } catch (TenantLifecycleException pending) {
                        assertEquals("IDEMPOTENCY_REQUEST_IN_PROGRESS", pending.code());
                        return null;
                    }
                }));
            }
            start.countDown();
            Set<UUID> ids = new java.util.HashSet<>();
            for (Future<UUID> attempt : attempts) {
                UUID id = attempt.get();
                if (id != null) ids.add(id);
            }
            assertEquals(1, ids.size());
            var records = recoverableCreation.list(actor, null, 50).items();
            assertEquals(1, records.size());
            assertTrue(ids.contains(records.get(0).tenantId()));
            assertEquals(1, tenantQueries.list("Concurrent recovery", null, null, 50).items().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void preservesExactExpiryForOriginalRequestFingerprintAndReplay() {
        UUID actor = uuidV7(840);
        UUID key = uuidV7(841);
        Instant expiry = Instant.parse("2030-01-01T00:00:00.123456789Z");
        var created = recoverableCreation.create(actor, key, "Precise expiry", expiry, null);
        assertEquals(expiry, created.expiresAt());
        assertEquals(created, recoverableCreation.create(actor, key, "Precise expiry", expiry, null));
        var attempt = recoverableCreation.list(actor, null, 50).items().get(0);
        assertEquals(created.id(), recoverableCreation.recover(actor, attempt.id(), key, null).tenantId());
        assertThrows(IdempotencyKeyReusedException.class,
                () -> recoverableCreation.create(actor, key, "Precise expiry", expiry.plusNanos(1), null));
    }

    @Test
    void retainsKnownResultsButNeverRestartsUnknownCreationsAtThe24HourBoundary() throws SQLException {
        UUID actor = uuidV7(820);
        UUID committedKey = uuidV7(821);
        UUID unknownKey = uuidV7(822);
        var created = recoverableCreation.create(actor, committedKey, "Boundary", null, null);
        executeAsMigrator("REVOKE INSERT ON tenants FROM tenant_access_app");
        try {
            assertThrows(RuntimeException.class,
                    () -> recoverableCreation.create(actor, unknownKey, "Boundary", null, null));
        } finally {
            executeAsMigrator("GRANT INSERT ON tenants TO tenant_access_app");
        }
        var records = recoverableCreation.list(actor, null, 50).items();
        var committed = records.stream().filter(item -> item.state() == TenantCreationRecovery.State.COMMITTED)
                .findFirst().orElseThrow();
        var uncommitted = records.stream().filter(item -> item.state() == TenantCreationRecovery.State.NOT_COMMITTED)
                .findFirst().orElseThrow();
        var before = new RecoverableTenantCreationService(service, creationRecoveryRepository,
                Clock.fixed(committed.replayUntil().minusMillis(1), ZoneOffset.UTC));
        assertEquals(created.id(), before.recover(actor, committed.id(), committedKey, null).tenantId());
        var atBoundary = new RecoverableTenantCreationService(service, creationRecoveryRepository,
                Clock.fixed(committed.replayUntil(), ZoneOffset.UTC));
        assertEquals(TenantCreationRecovery.State.COMMITTED, atBoundary.get(actor, committed.id()).state());
        assertFalse(atBoundary.get(actor, committed.id()).canReplay());
        assertNull(atBoundary.get(actor, committed.id()).idempotencyKey());
        assertThrows(TenantLifecycleException.class,
                () -> atBoundary.recover(actor, committed.id(), committedKey, null));
        var expired = new RecoverableTenantCreationService(service, creationRecoveryRepository,
                Clock.fixed(uncommitted.replayUntil(), ZoneOffset.UTC));
        assertEquals(TenantCreationRecovery.State.UNKNOWN, expired.get(actor, uncommitted.id()).state());
        assertFalse(expired.get(actor, uncommitted.id()).canReplay());
        assertThrows(TenantLifecycleException.class,
                () -> expired.recover(actor, uncommitted.id(), unknownKey, null));
        assertEquals(1, tenantQueries.list("Boundary", null, null, 50).items().size());
    }

    @Test
    void permitsCorrectingInvalidExpiryWithTheSameKeyAndRecoversATransactionRollback() throws SQLException {
        UUID actor = uuidV7(830);
        UUID key = uuidV7(831);
        assertThrows(io.saas.forge.tenantaccess.domain.tenant.TenantExpiryInvalidException.class,
                () -> recoverableCreation.create(actor, key, "Corrected", Instant.EPOCH, null));
        assertTrue(recoverableCreation.list(actor, null, 50).items().isEmpty());
        executeAsMigrator("REVOKE INSERT ON tenants FROM tenant_access_app");
        try {
            assertThrows(RuntimeException.class,
                    () -> recoverableCreation.create(actor, key, "Corrected", null, null));
        } finally {
            executeAsMigrator("GRANT INSERT ON tenants TO tenant_access_app");
        }
        var attempt = recoverableCreation.list(actor, null, 50).items().get(0);
        assertEquals(TenantCreationRecovery.State.NOT_COMMITTED, attempt.state());
        var result = recoverableCreation.recover(actor, attempt.id(), key, null);
        assertEquals(TenantCreationRecovery.State.COMMITTED, result.state());
        assertEquals("Corrected", tenantQueries.get(result.tenantId()).displayName());
        assertEquals(1, tenantQueries.list("Corrected", null, null, 50).items().size());
    }

    @Test
    void recoversOnlyOriginalActorsCommittedCreationWithoutMatchingByName() {
        UUID actor = uuidV7(810);
        TenantCreationResult created = recoverableCreation.create(actor, uuidV7(811), "Same", null, null);
        TenantCreationResult other = recoverableCreation.create(actor, uuidV7(812), "Same", null, null);
        var attempts = recoverableCreation.list(actor, null, 50);
        assertEquals(2, attempts.items().size());
        var attempt = attempts.items().stream().filter(item -> created.id().equals(item.tenantId())).findFirst().orElseThrow();
        assertEquals("COMMITTED", attempt.state().name());
        assertEquals(created.id(), recoverableCreation.recover(actor, attempt.id(), uuidV7(811), null).tenantId());
        assertNotEquals(other.id(), attempt.tenantId());
        assertTrue(recoverableCreation.list(uuidV7(813), null, 50).items().isEmpty());
        assertThrows(TenantLifecycleException.class,
                () -> recoverableCreation.get(uuidV7(813), attempt.id()));
    }

    @Test
    void listsSameNameTenantsWithBoundCursorsAndReadsAuthoritativeDetails() {
        UUID actor = uuidV7(800);
        TenantCreationResult first = service.create(actor, uuidV7(801), "Same name", null, null);
        TenantCreationResult second = service.create(actor, uuidV7(802), "Same name", null, null);
        service.create(actor, uuidV7(803), "Other", null, null);

        var page = tenantQueries.list("Same", TenantStatus.PENDING, null, 1);
        assertEquals(1, page.items().size());
        assertTrue(page.hasMore());
        var last = tenantQueries.list("Same", TenantStatus.PENDING, page.nextCursor(), 1);
        assertEquals(1, last.items().size());
        assertFalse(last.hasMore());
        assertNull(last.nextCursor());
        assertNotEquals(page.items().get(0).id(), last.items().get(0).id());
        assertEquals(Set.of(first.id(), second.id()),
                Set.of(page.items().get(0).id(), last.items().get(0).id()));
        assertEquals(first.id(), tenantQueries.get(first.id()).id());
        assertThrows(IllegalArgumentException.class,
                () -> tenantQueries.list("Other", TenantStatus.PENDING, page.nextCursor(), 1));
    }

    @Test
    void suspendsResumesAndReplaysHistoricalResultWithOriginalFenceGeneration() throws SQLException {
        UUID actor = uuidV7(900);
        TenantCreationResult created = service.create(actor, uuidV7(901), "Lifecycle Tenant", null, null);
        executeAsMigrator("UPDATE tenants SET tenant_status = 'ACTIVE' WHERE id = '" + created.id() + "'");
        sessionRevocations.results.add(SessionRevocationGateway.Result.completed(3, 7));

        TenantLifecycleResult suspended = tenantLifecycle.suspend(actor, uuidV7(902), created.id(), null);
        var suspendedProgress = tenantLifecycle.read(created.id());
        assertTrue(suspendedProgress.canResume());
        assertFalse(suspendedProgress.canSuspend());
        UUID revocationRequestId = sessionRevocations.lastRevocationRequestId;
        TenantLifecycleResult resumed = tenantLifecycle.resume(actor, uuidV7(903), created.id());
        var resumedProgress = tenantLifecycle.read(created.id());
        assertTrue(resumedProgress.canSuspend());
        assertFalse(resumedProgress.canResume());
        TenantLifecycleResult replay = tenantLifecycle.suspend(actor, uuidV7(902), created.id(), null);

        assertEquals(TenantStatus.SUSPENDED, suspended.status());
        assertEquals(TenantStatus.ACTIVE, resumed.status());
        assertEquals(suspended, replay);
        assertEquals(revocationRequestId, sessionRevocations.lastReleasedRevocationRequestId);
        String event = scalar("SELECT event_snapshot::text FROM tenant_access_outbox_events "
                + "WHERE event_snapshot->>'type' = 'com.saas.forge.tenant.suspended.v1'");
        assertTrue(event.contains("\"revokedSessionCount\": 3"));
        assertFalse(event.contains("revokedJtiCount"));
    }

    @Test
    void bindsExternalKeyRejectsOtherInFlightKeyAndReturnsPendingRetry() throws SQLException {
        UUID actor = uuidV7(910);
        TenantCreationResult created = service.create(actor, uuidV7(911), "Pending Tenant", null, null);
        executeAsMigrator("UPDATE tenants SET tenant_status = 'ACTIVE' WHERE id = '" + created.id() + "'");
        sessionRevocations.results.add(SessionRevocationGateway.Result.pending(4));

        TenantLifecycleException pending = assertThrows(TenantLifecycleException.class,
                () -> tenantLifecycle.suspend(actor, uuidV7(912), created.id(), null));
        TenantLifecycleException replayPending = assertThrows(TenantLifecycleException.class,
                () -> tenantLifecycle.suspend(actor, uuidV7(912), created.id(), null));
        TenantLifecycleException otherKey = assertThrows(TenantLifecycleException.class,
                () -> tenantLifecycle.suspend(actor, uuidV7(913), created.id(), null));
        assertThrows(IdempotencyKeyReusedException.class,
                () -> tenantLifecycle.resume(actor, uuidV7(912), created.id()));

        assertEquals("TENANT_SUSPENSION_PENDING", pending.code());
        assertTrue(pending.retryAfterSeconds() >= 1);
        assertEquals("TENANT_SUSPENSION_PENDING", replayPending.code());
        assertEquals("TENANT_LIFECYCLE_CHANGE_IN_PROGRESS", otherKey.code());

        executeAsMigrator("UPDATE tenant_lifecycle_workflows SET next_attempt_at = now() - interval '1 second' "
                + "WHERE tenant_id = '" + created.id() + "'");
        sessionRevocations.failures.add(new SessionRevocationUnavailableException(new RuntimeException()));
        TenantLifecycleWorker worker = new TenantLifecycleWorker(tenantLifecycle);
        TenantLifecycleException recoveryRequired = assertThrows(
                TenantLifecycleException.class, worker::recoverNext);
        assertEquals("TENANT_SUSPENSION_RECOVERY_REQUIRED", recoveryRequired.code());
    }

    @Test
    void distinguishesPreFenceRetryFromFailClosedExplicitRecovery() throws SQLException {
        UUID actor = uuidV7(920);
        TenantCreationResult first = service.create(actor, uuidV7(921), "Pre Fence", null, null);
        executeAsMigrator("UPDATE tenants SET tenant_status = 'ACTIVE' WHERE id = '" + first.id() + "'");
        sessionRevocations.failures.add(new SessionRevocationRejectedException());
        TenantLifecycleException preFence = assertThrows(TenantLifecycleException.class,
                () -> tenantLifecycle.suspend(actor, uuidV7(922), first.id(), null));
        assertEquals("TENANT_SUSPENSION_RETRY_REQUIRED", preFence.code());

        sessionRevocations.results.add(SessionRevocationGateway.Result.completed(0, 0));
        assertEquals(TenantStatus.SUSPENDED,
                tenantLifecycle.suspend(actor, uuidV7(923), first.id(), null).status());

        TenantCreationResult second = service.create(actor, uuidV7(924), "Post Fence", null, null);
        executeAsMigrator("UPDATE tenants SET tenant_status = 'ACTIVE' WHERE id = '" + second.id() + "'");
        sessionRevocations.failures.add(new SessionRevocationUnavailableException(new RuntimeException()));
        TenantLifecycleException failClosed = assertThrows(TenantLifecycleException.class,
                () -> tenantLifecycle.suspend(actor, uuidV7(925), second.id(), null));
        assertEquals("TENANT_SUSPENSION_RECOVERY_REQUIRED", failClosed.code());
        UUID originalRequestId = sessionRevocations.lastRevocationRequestId;
        assertThrows(TenantLifecycleException.class,
                () -> tenantLifecycle.suspend(actor, uuidV7(926), second.id(), null));

        sessionRevocations.results.add(SessionRevocationGateway.Result.completed(2, 5));
        TenantLifecycleResult recovered = tenantLifecycle.recoverSuspension(
                actor, uuidV7(927), second.id(), null);
        assertEquals(TenantStatus.SUSPENDED, recovered.status());
        assertEquals(originalRequestId, sessionRevocations.lastRecoveredRevocationRequestId);
        assertEquals(originalRequestId, sessionRevocations.lastRevocationRequestId);
    }

    @Test
    void expiredLeaseCanBeTakenOverAndStaleFencingTokenCannotComplete() throws SQLException {
        UUID actor = uuidV7(930);
        TenantCreationResult created = service.create(actor, uuidV7(931), "Lease Tenant", null, null);
        executeAsMigrator("UPDATE tenants SET tenant_status = 'ACTIVE' WHERE id = '" + created.id() + "'");
        Instant now = Instant.now();
        TenantLifecycleWorkflow workflow = tenantLifecycleWorkflows.prepare(
                actor, uuidV7(932), created.id(), TenantLifecycleAction.SUSPEND,
                "a".repeat(64), uuidV7(933), uuidV7(934), null, now).workflow();
        TenantLifecycleWorkflow first = tenantLifecycleWorkflows.claim(
                workflow.workflowId(), "worker-a", now, now.plusMillis(1), 10).orElseThrow();
        TenantLifecycleWorkflow takeover = tenantLifecycleWorkflows.claimNext(
                "worker-b", now.plusSeconds(1), now.plusSeconds(31), 10).orElseThrow();

        assertTrue(takeover.fencingToken() > first.fencingToken());
        assertThrows(IllegalStateException.class,
                () -> tenantLifecycleWorkflows.schedulePending(first, now.plusSeconds(2)));
    }

    @AfterAll
    void stop() {
        KAFKA.stop();
        POSTGRES.stop();
    }

    @Test
    void commitsTenantStableResponseAndCreatedOutboxTogether() throws SQLException {
        UUID actor = uuidV7(1);
        TenantCreationResult result = service.create(
                actor, uuidV7(2), "Atomic Tenant", null,
                "11111111111111111111111111111111");
        TenantCreationResult replay = service.create(
                actor, uuidV7(2), "Atomic Tenant", result.expiresAt(),
                "11111111111111111111111111111111");

        assertEquals(result, replay);
        assertEquals(1, count("tenants"));
        assertEquals(1, count("tenant_creation_idempotency"));
        assertEquals(1, count("tenant_access_outbox_events"));
        assertEquals("PENDING", scalar("SELECT tenant_status FROM tenants WHERE id = '" + result.id() + "'"));
        assertNull(result.expiresAt());
        assertNull(scalar("SELECT expires_at::text FROM tenants WHERE id = '" + result.id() + "'"));
        assertTrue(scalar("SELECT event_snapshot::text FROM tenant_access_outbox_events")
                .contains("com.saas.forge.tenant.created.v1"));
    }

    @Test
    void publishesCommittedTenantFactThroughKafkaAndMarksPostgreSqlOutbox() throws SQLException {
        try (KafkaConsumer<String, String> consumer = kafkaConsumer()) {
            consumer.subscribe(List.of("saas.forge.test.tenant-access-service.events"));
            consumer.poll(Duration.ofMillis(250));
            TenantCreationResult tenant = service.create(
                    uuidV7(5), uuidV7(6), "Kafka Tenant", Instant.now().plusSeconds(3600), null);

            outboxPublisher.publishNext();
            // Topic 保留同类其他用例的消息，必须等待本次创建事实。
            ConsumerRecord<String, String> event = awaitEventContaining(consumer, tenant.id().toString());

            assertEquals(tenant.id().toString(), event.key());
            assertTrue(event.value().contains("com.saas.forge.tenant.created.v1"));
            assertEquals("true", scalar("SELECT (published_at IS NOT NULL)::text FROM tenant_access_outbox_events"));
        }
    }

    @Test
    void activatesTenantWithOneInitialAdministratorRoleAndPendingPasswordDelivery() throws SQLException {
        UUID actor = uuidV7(60);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(61), "Admin Tenant", null, null);
        Instant now = Instant.now();
        InitializationWorkflow workflow = new InitializationWorkflow(
                uuidV7(62), tenant.id(), actor, uuidV7(63), "a".repeat(64),
                "admin@example.com", "Admin", uuidV7(64), uuidV7(65), uuidV7(66), uuidV7(67),
                null, null, null, now);

        InitializationWorkflow prepared = administratorInitialization.prepare(workflow, now);
        InitializationWorkflow claimed = administratorInitialization.claim(
                prepared.workflowId(), "postgres-it", now, now.plusSeconds(30)).orElseThrow();
        InitializationWorkflow identityReady = administratorInitialization.completeIdentity(
                claimed, uuidV7(68), IdentityCredentialDisposition.SETUP_ALLOWED, now.plusMillis(1));
        InitializationWorkflow quotaConsumed = administratorInitialization.completeQuotaConsumption(
                identityReady, now.plusMillis(2));
        InitializationWorkflow activating = administratorInitialization.beginActivation(
                quotaConsumed, now.plusMillis(3));
        TenantAdministratorInitializationResult result = administratorInitialization.activate(
                activating, uuidV7(68), IdentityCredentialDisposition.SETUP_ALLOWED, now.plusMillis(4));

        assertEquals("ACTIVE", result.status().name());
        assertNull(result.expiresAt());
        assertEquals("ACTIVE", scalar("SELECT tenant_status FROM tenants WHERE id = '" + tenant.id() + "'"));
        assertEquals(1, count("memberships"));
        assertEquals(1, count("tenant_roles"));
        assertEquals(1, count("membership_role_assignments"));
        assertEquals(1, count("initial_tenant_administrators"));
        assertEquals(1, count("password_setup_delivery_work_items"));
        assertEquals("TENANT_ADMINISTRATOR", scalar("SELECT role_key FROM tenant_roles"));
        assertEquals("true", scalar("SELECT system_managed::text FROM tenant_roles"));
        assertEquals("SUCCESS", scalar("SELECT outcome_code FROM tenant_administrator_initialization_workflows"));
        assertEquals("200", scalar("SELECT response_status::text FROM tenant_administrator_initialization_workflows"));
        assertTrue(scalar("SELECT event_snapshot::text FROM tenant_access_outbox_events "
                + "WHERE event_snapshot->>'type' = 'com.saas.forge.tenant.administrator-initialized.v1'")
                .contains(tenant.id().toString()));

        InitializationWorkflow replay = administratorInitialization.prepare(workflow, now.plusSeconds(1));
        assertEquals(result, replay.result());
        assertEquals(1, count("memberships"));
        assertEquals(1, count("tenant_roles"));

        InitializationWorkflow newKey = administratorInitialization.prepare(
                workflow(tenant.id(), actor, uuidV7(69), "f".repeat(64)), now.plusSeconds(1));
        assertEquals("TENANT_ALREADY_INITIALIZED", newKey.outcomeCode());

        administratorInitialization.completePasswordDelivery(activating, now.plusSeconds(2));
        assertEquals("COMPLETED", scalar("SELECT work_status FROM password_setup_delivery_work_items"));
    }

    @Test
    void passwordSetupResendUsesInitialRelationshipSupersedesOldWorkAndSerializesTenantLeases()
            throws SQLException {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID actor = uuidV7(610);
        UUID identityId = uuidV7(611);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(612), "Resend Tenant", now.plusSeconds(3600), null);
        InitializationWorkflow prepared = administratorInitialization.prepare(
                workflow(tenant.id(), actor, uuidV7(613), "6".repeat(64)), now);
        InitializationWorkflow claimed = administratorInitialization.claim(
                prepared.workflowId(), "initialization", now, now.plusSeconds(30)).orElseThrow();
        InitializationWorkflow identityReady = administratorInitialization.completeIdentity(
                claimed, identityId, IdentityCredentialDisposition.SETUP_ALLOWED, now.plusMillis(1));
        InitializationWorkflow quotaReady = administratorInitialization.completeQuotaConsumption(
                identityReady, now.plusMillis(2));
        InitializationWorkflow activating = administratorInitialization.beginActivation(
                quotaReady, now.plusMillis(3));
        administratorInitialization.activate(
                activating, identityId, IdentityCredentialDisposition.SETUP_ALLOWED, now.plusMillis(4));

        AdministratorPasswordSetupWorkflow first = administratorPasswordSetups.prepare(
                passwordSetupWorkflow(tenant.id(), actor, uuidV7(614), uuidV7(615), uuidV7(616), now), now);
        AdministratorPasswordSetupWorkflow second = administratorPasswordSetups.prepare(
                passwordSetupWorkflow(tenant.id(), actor, uuidV7(617), uuidV7(618), uuidV7(619),
                        now.plusMillis(1)), now.plusMillis(1));

        assertEquals(identityId, first.administratorIdentityId());
        AdministratorPasswordSetupWorkflow firstLease = administratorPasswordSetups.claim(
                first.workflowId(), "resend-a", now.plusSeconds(31), now.plusSeconds(61)).orElseThrow();
        assertEquals("SUPERSEDED", scalar("SELECT work_status FROM password_setup_delivery_work_items"));
        assertTrue(administratorPasswordSetups.claim(
                second.workflowId(), "resend-b", now.plusSeconds(31), now.plusSeconds(61)).isEmpty());

        administratorPasswordSetups.scheduleRetry(
                firstLease, now.plusSeconds(40), "RemoteWorkflowUnavailableException");
        assertTrue(administratorPasswordSetups.claimNext(
                "resend-worker", now.plusSeconds(32), now.plusSeconds(62)).isEmpty());
        AdministratorPasswordSetupWorkflow retriedFirst = administratorPasswordSetups.claimNext(
                "resend-worker", now.plusSeconds(40), now.plusSeconds(70)).orElseThrow();
        assertEquals(first.workflowId(), retriedFirst.workflowId());
        administratorPasswordSetups.completeSuccess(retriedFirst, now.plusSeconds(41));
        AdministratorPasswordSetupWorkflow secondLease = administratorPasswordSetups.claimNext(
                "resend-b", now.plusSeconds(41), now.plusSeconds(71)).orElseThrow();
        assertEquals(second.deliveryRequestId(), secondLease.deliveryRequestId());
        assertEquals("true", scalar("SELECT relforcerowsecurity::text FROM pg_class "
                + "WHERE relname = 'administrator_password_setup_workflows'"));
        assertEquals("0", scalar("SELECT count(*)::text FROM information_schema.columns "
                + "WHERE table_name = 'administrator_password_setup_workflows' "
                + "AND column_name IN ('email', 'recipient', 'token', 'token_digest', 'mail_content')"));
    }

    @Test
    void passwordSetupWorkerReclaimsSameDurableRequestAfterRestart() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID actor = uuidV7(620);
        UUID identityId = uuidV7(621);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(622), "Resend Recovery Tenant", now.plusSeconds(3600), null);
        InitializationWorkflow prepared = administratorInitialization.prepare(
                workflow(tenant.id(), actor, uuidV7(623), "7".repeat(64)), now);
        InitializationWorkflow claimed = administratorInitialization.claim(
                prepared.workflowId(), "initialization", now, now.plusSeconds(30)).orElseThrow();
        InitializationWorkflow identityReady = administratorInitialization.completeIdentity(
                claimed, identityId, IdentityCredentialDisposition.PASSWORD_READY, now.plusMillis(1));
        InitializationWorkflow quotaReady = administratorInitialization.completeQuotaConsumption(
                identityReady, now.plusMillis(2));
        InitializationWorkflow activating = administratorInitialization.beginActivation(
                quotaReady, now.plusMillis(3));
        administratorInitialization.activate(
                activating, identityId, IdentityCredentialDisposition.PASSWORD_READY, now.plusMillis(4));

        AdministratorPasswordSetupWorkflow workflow = administratorPasswordSetups.prepare(
                passwordSetupWorkflow(tenant.id(), actor, uuidV7(624), uuidV7(625), uuidV7(626), now), now);
        AdministratorPasswordSetupWorkflow firstLease = administratorPasswordSetups.claim(
                workflow.workflowId(), "process-a", now.plusSeconds(31), now.plusSeconds(61)).orElseThrow();
        administratorPasswordSetups.scheduleRetry(
                firstLease, now.plusSeconds(32), "RemoteWorkflowUnavailableException");

        AdministratorPasswordSetupWorkflow restarted = administratorPasswordSetups.claimNext(
                "process-b", now.plusSeconds(32), now.plusSeconds(62)).orElseThrow();
        assertEquals(workflow.workflowId(), restarted.workflowId());
        assertEquals(workflow.deliveryRequestId(), restarted.deliveryRequestId());
        assertEquals(firstLease.attemptCount() + 1, restarted.attemptCount());
    }

    @Test
    void persistsStableQuotaFailureThroughForcedRls() throws SQLException {
        UUID actor = uuidV7(80);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(81), "Quota Tenant", Instant.now().plusSeconds(3600), null);
        InitializationWorkflow prepared = administratorInitialization.prepare(
                workflow(tenant.id(), actor, uuidV7(82), "e".repeat(64)), Instant.now());
        InitializationWorkflow claimed = administratorInitialization.claim(
                prepared.workflowId(), "postgres-it", Instant.now(), Instant.now().plusSeconds(30)).orElseThrow();

        administratorInitialization.completeFailure(
                claimed, "QUOTA_EXCEEDED", Instant.now());
        InitializationWorkflow replay = administratorInitialization.prepare(prepared, Instant.now());

        assertEquals("QUOTA_EXCEEDED", replay.outcomeCode());
        InitializationWorkflow laterReplay = administratorInitialization.prepare(prepared, Instant.now().plus(Duration.ofDays(3)));
        assertEquals(prepared.workflowId(), laterReplay.workflowId());
        assertEquals("QUOTA_EXCEEDED", laterReplay.outcomeCode());
        assertEquals("409", scalar("SELECT response_status::text FROM tenant_administrator_initialization_workflows"));
        assertEquals("true", scalar("SELECT relforcerowsecurity::text FROM pg_class "
                + "WHERE relname = 'tenant_administrator_initialization_workflows'"));
    }

    @Test
    void workerClaimRestoresAuthoritativeTargetAndExpiredLeaseTakeoverFencesOldExecutor() {
        Instant now = Instant.now();
        UUID actor = uuidV7(90);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(91), "Lease Tenant", now.plusSeconds(3600), null);
        InitializationWorkflow prepared = administratorInitialization.prepare(
                workflow(tenant.id(), actor, uuidV7(92), "9".repeat(64)), now);

        InitializationWorkflow first = administratorInitialization.claimNext(
                "worker-a", now, now.plusSeconds(10)).orElseThrow();
        assertEquals(prepared.workflowId(), first.workflowId());
        assertEquals(tenant.id(), first.tenantId());
        InitializationWorkflow identityReady = administratorInitialization.completeIdentity(
                first, uuidV7(93), IdentityCredentialDisposition.PASSWORD_READY, now.plusSeconds(1));
        InitializationWorkflow quotaConsumed = administratorInitialization.completeQuotaConsumption(
                identityReady, now.plusSeconds(2));
        InitializationWorkflow activating = administratorInitialization.beginActivation(
                quotaConsumed, now.plusSeconds(3));

        assertTrue(administratorInitialization.claimNext(
                "worker-b", now.plusSeconds(9), now.plusSeconds(20)).isEmpty());
        InitializationWorkflow takeover = administratorInitialization.claimNext(
                "worker-b", now.plusSeconds(11), now.plusSeconds(21)).orElseThrow();
        assertEquals(InitializationWorkflowState.ACTIVATING, takeover.state());
        assertEquals(activating.attemptCount() + 1, takeover.attemptCount());
        assertThrows(IllegalStateException.class,
                () -> administratorInitialization.beginCompensation(activating, now.plusSeconds(12)));
        InitializationWorkflow compensating = administratorInitialization.beginCompensation(
                takeover, now.plusSeconds(12));
        assertEquals(InitializationWorkflowState.COMPENSATING, compensating.state());
    }

    @Test
    void workerRestartExhaustionRemainsDiagnosticAndExplicitReplayPublishesThroughKafka() throws SQLException {
        Instant now = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
        UUID actor = uuidV7(94);
        UUID idempotencyKey = uuidV7(95);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(96), "Restart Tenant", now.plusSeconds(3600), null);
        AtomicBoolean identityAvailable = new AtomicBoolean();
        List<UUID> identityRequests = new ArrayList<>();
        IdentityProvisioningGateway identities = (requestId, email, displayName) -> {
            identityRequests.add(requestId);
            if (!identityAvailable.get()) {
                throw new RemoteWorkflowUnavailableException(new IllegalStateException("IAM unavailable"));
            }
            return new IdentityProvisioningGateway.Result(uuidV7(97), IdentityCredentialDisposition.PASSWORD_READY);
        };
        InitializationQuotaGateway quota = new InitializationQuotaGateway() {
            @Override
            public void consume(UUID tenantId, UUID operationId) {
            }

            @Override
            public void release(UUID tenantId, UUID operationId) {
            }
        };
        InitializationRecoveryPolicy policy = new InitializationRecoveryPolicy(
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1), 2);
        InitializeTenantAdministratorService firstProcess = new InitializeTenantAdministratorService(
                administratorInitialization, identities, quota, (requestId, identityId) -> { },
                new UuidV7Generator(Clock.fixed(now, ZoneOffset.UTC), new SecureRandom()),
                Clock.fixed(now, ZoneOffset.UTC), policy, "process-a");

        assertThrows(RemoteWorkflowUnavailableException.class, () -> firstProcess.initialize(
                actor, idempotencyKey, tenant.id(), "admin@example.com", "Admin", null));

        InitializeTenantAdministratorService restartedWorker = new InitializeTenantAdministratorService(
                administratorInitialization, identities, quota, (requestId, identityId) -> { },
                new UuidV7Generator(Clock.fixed(now.plusSeconds(2), ZoneOffset.UTC), new SecureRandom()),
                Clock.fixed(now.plusSeconds(2), ZoneOffset.UTC), policy, "process-b");
        assertTrue(restartedWorker.recoverNext());
        assertEquals("true", scalar("SELECT (recovery_exhausted_at IS NOT NULL)::text "
                + "FROM tenant_administrator_initialization_workflows"));
        assertEquals("RemoteWorkflowUnavailableException", scalar(
                "SELECT last_failure FROM tenant_administrator_initialization_workflows"));
        assertNull(scalar("SELECT outcome_code FROM tenant_administrator_initialization_workflows"));
        assertFalse(restartedWorker.recoverNext());

        identityAvailable.set(true);
        TenantAdministratorInitializationResult result = restartedWorker.initialize(
                actor, idempotencyKey, tenant.id(), "admin@example.com", "Admin", null);
        assertEquals(TenantStatus.ACTIVE, result.status());
        assertEquals(3, identityRequests.size());
        assertEquals(1, identityRequests.stream().distinct().count());
        assertEquals("false", scalar("SELECT (recovery_exhausted_at IS NOT NULL)::text "
                + "FROM tenant_administrator_initialization_workflows"));

        try (KafkaConsumer<String, String> consumer = kafkaConsumer()) {
            consumer.subscribe(List.of("saas.forge.test.tenant-access-service.events"));
            consumer.poll(Duration.ofMillis(250));
            outboxPublisher.publishNext();
            outboxPublisher.publishNext();
            ConsumerRecord<String, String> event = awaitEventContaining(
                    consumer, TenantAdministratorInitializedEventFactory.EVENT_TYPE);
            assertEquals(tenant.id().toString(), event.key());
        }
    }

    @Test
    void localActivationCommitFailureUsesStableReleaseAndRequiresNewAttempt() throws SQLException {
        Instant now = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
        UUID actor = uuidV7(100);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(101), "Compensation Tenant", now.plusSeconds(3600), null);
        List<UUID> consumed = new ArrayList<>();
        List<UUID> released = new ArrayList<>();
        AtomicBoolean releaseUnavailable = new AtomicBoolean(true);
        InitializationQuotaGateway quota = new InitializationQuotaGateway() {
            @Override
            public void consume(UUID tenantId, UUID operationId) {
                consumed.add(operationId);
            }

            @Override
            public void release(UUID tenantId, UUID operationId) {
                released.add(operationId);
                if (releaseUnavailable.get()) {
                    throw new RemoteWorkflowUnavailableException(
                            new IllegalStateException("Entitlement unavailable"));
                }
            }
        };
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InitializationRecoveryPolicy policy = new InitializationRecoveryPolicy(
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1), 10);
        InitializeTenantAdministratorService initialization = new InitializeTenantAdministratorService(
                administratorInitialization,
                (requestId, email, displayName) -> new IdentityProvisioningGateway.Result(
                        uuidV7(102), IdentityCredentialDisposition.PASSWORD_READY),
                quota,
                (requestId, identityId) -> { },
                new UuidV7Generator(clock, new SecureRandom()), clock, policy, "process-a");

        executeAsMigrator("""
                CREATE OR REPLACE FUNCTION fail_tenant_admin_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced local activation failure'; END $$;
                CREATE TRIGGER fail_tenant_admin_outbox BEFORE INSERT ON tenant_access_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_tenant_admin_outbox()
                """);
        UUID firstKey = uuidV7(103);
        try {
            TenantAdministratorInitializationException failure = assertThrows(
                    TenantAdministratorInitializationException.class,
                    () -> initialization.initialize(
                            actor, firstKey, tenant.id(), "admin@example.com", "Admin", null));
            assertEquals("TENANT_ADMIN_INITIALIZATION_COMPENSATING", failure.code());
        } finally {
            executeAsMigrator("DROP TRIGGER fail_tenant_admin_outbox ON tenant_access_outbox_events; "
                    + "DROP FUNCTION fail_tenant_admin_outbox()");
        }

        assertEquals(1, consumed.size());
        assertEquals(List.of(UUID.fromString(scalar(
                "SELECT release_operation_id::text FROM tenant_administrator_initialization_workflows "
                        + "WHERE idempotency_key = '" + firstKey + "'"))), released);
        assertEquals("COMPENSATING", scalar(
                "SELECT workflow_state FROM tenant_administrator_initialization_workflows "
                        + "WHERE idempotency_key = '" + firstKey + "'"));
        assertEquals("PENDING", scalar("SELECT tenant_status FROM tenants WHERE id = '" + tenant.id() + "'"));
        assertEquals(0, count("memberships"));

        releaseUnavailable.set(false);
        Clock restartedClock = Clock.fixed(now.plusSeconds(2), ZoneOffset.UTC);
        InitializeTenantAdministratorService restartedWorker = new InitializeTenantAdministratorService(
                administratorInitialization,
                (requestId, email, displayName) -> new IdentityProvisioningGateway.Result(
                        uuidV7(102), IdentityCredentialDisposition.PASSWORD_READY),
                quota,
                (requestId, identityId) -> { },
                new UuidV7Generator(restartedClock, new SecureRandom()), restartedClock, policy, "process-b");
        assertTrue(restartedWorker.recoverNext());
        assertEquals(2, released.size());
        assertEquals(released.get(0), released.get(1));
        assertEquals("TENANT_ADMIN_INITIALIZATION_RETRY_REQUIRED", scalar(
                "SELECT outcome_code FROM tenant_administrator_initialization_workflows "
                        + "WHERE idempotency_key = '" + firstKey + "'"));

        TenantAdministratorInitializationException replay = assertThrows(
                TenantAdministratorInitializationException.class,
                () -> restartedWorker.initialize(
                        actor, firstKey, tenant.id(), "admin@example.com", "Admin", null));
        assertEquals("TENANT_ADMIN_INITIALIZATION_RETRY_REQUIRED", replay.code());
        assertEquals(1, consumed.size());
        assertEquals(2, released.size());

        UUID secondKey = uuidV7(104);
        TenantAdministratorInitializationResult result = restartedWorker.initialize(
                actor, secondKey, tenant.id(), "admin@example.com", "Admin", null);
        assertEquals(TenantStatus.ACTIVE, result.status());
        assertEquals(2, consumed.size());
        assertNotEquals(consumed.get(0), consumed.get(1));
    }

    @Test
    void rejectsExpiredTenantBeforeRemoteWorkAndSerializesConcurrentKeys() throws Exception {
        UUID expiredTenant = uuidV7(70);
        insertTenant(expiredTenant, "PENDING", Instant.now().minusSeconds(1));
        InitializationWorkflow expired = administratorInitialization.prepare(
                workflow(expiredTenant, uuidV7(71), uuidV7(72), "b".repeat(64)), Instant.now());
        assertEquals("TENANT_EXPIRY_REACHED", expired.outcomeCode());

        UUID concurrentTenant = uuidV7(73);
        insertTenant(concurrentTenant, "PENDING", Instant.now().plusSeconds(3600));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> prepareOutcome(
                    workflow(concurrentTenant, uuidV7(74), uuidV7(75), "c".repeat(64)), start));
            Future<String> second = executor.submit(() -> prepareOutcome(
                    workflow(concurrentTenant, uuidV7(74), uuidV7(76), "d".repeat(64)), start));
            start.countDown();

            assertEquals(Set.of("PREPARED", "TENANT_ADMIN_INITIALIZATION_IN_PROGRESS"),
                    Set.of(first.get(), second.get()));
        } finally {
            executor.shutdownNow();
        }
    }

    private String prepareOutcome(InitializationWorkflow workflow, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            return administratorInitialization.prepare(workflow, Instant.now()).completed()
                    ? "COMPLETED"
                    : "PREPARED";
        } catch (TenantAdministratorInitializationException exception) {
            return exception.code();
        }
    }

    private static InitializationWorkflow workflow(
            UUID tenantId, UUID actorIdentityId, UUID idempotencyKey, String fingerprint) {
        return new InitializationWorkflow(
                uuidV7(idempotencyKey.getLeastSignificantBits() & 0xfff), tenantId, actorIdentityId,
                idempotencyKey, fingerprint, "admin@example.com", "Admin",
                uuidV7((idempotencyKey.getLeastSignificantBits() & 0xfff) + 100),
                uuidV7((idempotencyKey.getLeastSignificantBits() & 0xfff) + 200),
                uuidV7((idempotencyKey.getLeastSignificantBits() & 0xfff) + 300),
                uuidV7((idempotencyKey.getLeastSignificantBits() & 0xfff) + 400),
                null, null, null, Instant.now());
    }

    private static AdministratorPasswordSetupWorkflow passwordSetupWorkflow(
            UUID tenantId,
            UUID actorIdentityId,
            UUID idempotencyKey,
            UUID workflowId,
            UUID deliveryRequestId,
            Instant createdAt) {
        return new AdministratorPasswordSetupWorkflow(
                workflowId, tenantId, actorIdentityId, idempotencyKey,
                "8".repeat(64),
                null, deliveryRequestId, null, null, createdAt, 0, createdAt,
                null, null, null, null);
    }

    @Test
    void concurrentSameCallerAndKeyCreatesExactlyOneTenant() throws Exception {
        UUID actor = uuidV7(10);
        UUID key = uuidV7(11);
        Instant expiry = Instant.now().plusSeconds(3600);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TenantCreationResult> first = executor.submit(() -> {
                start.await();
                return service.create(actor, key, "Concurrent", expiry, null);
            });
            Future<TenantCreationResult> second = executor.submit(() -> {
                start.await();
                return service.create(actor, key, "Concurrent", expiry, null);
            });
            start.countDown();

            assertEquals(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("tenants"));
        assertEquals(1, count("tenant_access_outbox_events"));
    }

    @Test
    void derivesInitialSubscriptionEligibilityThroughForcedTenantRls() throws SQLException {
        UUID eligibleTenant = uuidV7(50);
        UUID expiredTenant = uuidV7(51);
        UUID activeTenant = uuidV7(52);
        insertTenant(eligibleTenant, "PENDING", Instant.now().plusSeconds(3600));
        insertTenant(expiredTenant, "PENDING", Instant.now().minusSeconds(1));
        insertTenant(activeTenant, "ACTIVE", Instant.now().plusSeconds(3600));

        assertEquals(InitialSubscriptionEligibility.PENDING_ELIGIBLE, eligibility.check(eligibleTenant));
        assertEquals(InitialSubscriptionEligibility.EXPIRY_REACHED, eligibility.check(expiredTenant));
        assertEquals(InitialSubscriptionEligibility.INVALID_STATE, eligibility.check(activeTenant));
        assertEquals(InitialSubscriptionEligibility.NOT_FOUND, eligibility.check(uuidV7(53)));
    }

    private static void insertTenant(UUID tenantId, String status, Instant expiresAt) throws SQLException {
        executeAsMigrator("INSERT INTO tenants "
                + "(id, display_name, tenant_status, expires_at, created_at, updated_at) VALUES ('"
                + tenantId + "', 'Eligibility', '" + status + "', '" + expiresAt + "', now(), now())");
    }

    @Test
    void rejectsRequestFingerprintConflict() {
        UUID actor = uuidV7(20);
        UUID key = uuidV7(21);
        Instant expiry = Instant.now().plusSeconds(3600);
        service.create(actor, key, "First", expiry, null);

        assertThrows(IdempotencyKeyReusedException.class,
                () -> service.create(actor, key, "Second", expiry, null));
    }

    @Test
    void forcedRlsRejectsCrossTenantReadsAndAppRoleCannotBypass() throws SQLException {
        UUID actor = uuidV7(30);
        TenantCreationResult first = service.create(
                actor, uuidV7(31), "First", Instant.now().plusSeconds(3600), null);
        TenantCreationResult second = service.create(
                actor, uuidV7(32), "Second", Instant.now().plusSeconds(3600), null);

        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            setTarget(connection, first.id());
            assertEquals(1, queryCount(connection, "SELECT count(*) FROM tenants WHERE id = ?", first.id()));
            assertEquals(0, queryCount(connection, "SELECT count(*) FROM tenants WHERE id = ?", second.id()));
            connection.rollback();
        }
        assertFalse(Boolean.parseBoolean(scalar(
                "SELECT rolbypassrls::text FROM pg_roles WHERE rolname = 'tenant_access_app'")));
        assertEquals("true", scalar("SELECT relforcerowsecurity::text FROM pg_class WHERE relname = 'tenants'"));
    }

    @Test
    void outboxFailureRollsBackTenantAndIdempotencyRecord() throws SQLException {
        executeAsMigrator("""
                CREATE OR REPLACE FUNCTION fail_tenant_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced outbox failure'; END $$;
                CREATE TRIGGER fail_tenant_outbox BEFORE INSERT ON tenant_access_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_tenant_outbox()
                """);
        try {
            assertThrows(RuntimeException.class, () -> service.create(
                    uuidV7(40), uuidV7(41), "Rollback", Instant.now().plusSeconds(3600), null));
            assertEquals(0, count("tenants"));
            assertEquals(0, count("tenant_creation_idempotency"));
            assertEquals(0, count("tenant_access_outbox_events"));
        } finally {
            executeAsMigrator("DROP TRIGGER fail_tenant_outbox ON tenant_access_outbox_events; DROP FUNCTION fail_tenant_outbox()");
        }
    }

    private static int count(String table) throws SQLException {
        return Integer.parseInt(scalar("SELECT count(*)::text FROM " + table));
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection connection = migratorConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void executeAsMigrator(String sql) throws SQLException {
        try (Connection connection = migratorConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void setTarget(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenantId.toString());
            statement.executeQuery();
        }
    }

    private static int queryCount(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static Connection migratorConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                jdbcUrl(), "tenant_access_migrator", "tenant-access-migrator-password");
    }

    private static Connection appConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                jdbcUrl(), "tenant_access_app", "tenant-access-app-password");
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/tenant_access_db";
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
    }

    private static ConsumerRecord<String, String> awaitEventContaining(
            KafkaConsumer<String, String> consumer, String expectedContent) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (record.value().contains(expectedContent)) {
                    return record;
                }
            }
        }
        throw new AssertionError("未收到预期 Tenant Access Outbox 事件: " + expectedContent);
    }

    private static KafkaConsumer<String, String> kafkaConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "tenant-access-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(properties);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("deploy/postgresql/bootstrap.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位仓库根目录");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @MapperScan(
            basePackages = "io.saas.forge.tenantaccess.infrastructure.persistence.mapper",
            sqlSessionFactoryRef = "tenantAccessSqlSessionFactory")
    @Import({io.saas.forge.tenantaccess.infrastructure.persistence.MyBatisAdministratorPasswordSetupQueries.class, io.saas.forge.tenantaccess.infrastructure.persistence.MyBatisAdministratorInitializationQueries.class, MyBatisTenantRepository.class, MyBatisTenantCreationIdempotency.class,
            MyBatisTenantAccessOutboxEventRepository.class,
            MyBatisTenantAdministratorInitializationRepository.class,
            MyBatisAdministratorPasswordSetupRepository.class,
            MyBatisTenantLifecycleRepository.class, TenantQueryService.class, RecoverableTenantCreationService.class,
            io.saas.forge.tenantaccess.infrastructure.persistence.MyBatisTenantCreationRecovery.class,
            io.saas.forge.tenantaccess.infrastructure.persistence.MyBatisTenantQueries.class})
    static class PersistenceConfiguration {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(jdbcUrl(), "tenant_access_app", "tenant-access-app-password");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory tenantAccessSqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/*Mapper.xml"));
            factory.setTypeHandlersPackage("io.saas.forge.tenantaccess.infrastructure.persistence.type");
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory tenantAccessSqlSessionFactory) {
            return new SqlSessionTemplate(tenantAccessSqlSessionFactory);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        UuidV7Generator ids(Clock clock) {
            return new UuidV7Generator(clock, new SecureRandom());
        }

        @Bean
        KafkaTemplate<String, String> kafkaTemplate() {
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ProducerConfig.ACKS_CONFIG, "all",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
        }

        @Bean
        TenantAccessOutboxPublisher outboxPublisher(
                OutboxEventRepository repository,
                KafkaTemplate<String, String> kafkaTemplate,
                Clock clock) {
            return new TenantAccessOutboxPublisher(repository, kafkaTemplate, clock, Duration.ofSeconds(30));
        }

        @Bean
        TenantCreatedEventFactory eventFactory(ObjectMapper objectMapper, UuidV7Generator ids) {
            return new TenantCreatedEventFactory(
                    objectMapper, ids, "saas.forge.test.tenant-access-service.events");
        }

        @Bean
        TenantSuspendedEventFactory tenantSuspendedEventFactory(ObjectMapper objectMapper, UuidV7Generator ids) {
            return new TenantSuspendedEventFactory(
                    objectMapper, ids, "saas.forge.test.tenant-access-service.events");
        }

        @Bean
        ScriptedSessionRevocationGateway sessionRevocations() {
            return new ScriptedSessionRevocationGateway();
        }

        @Bean
        TenantLifecycleService tenantLifecycleService(
                TenantLifecycleRepository workflows,
                ScriptedSessionRevocationGateway revocations,
                TenantSuspendedEventFactory events,
                UuidV7Generator ids,
                Clock clock) {
            return new TenantLifecycleService(workflows, revocations, events, ids,
                    new TenantLifecycleRecoveryPolicy(Duration.ofSeconds(30), Duration.ofSeconds(1), 1),
                    clock, "postgres-it");
        }

        @Bean
        TenantAdministratorInitializedEventFactory administratorInitializedEventFactory(
                ObjectMapper objectMapper, UuidV7Generator ids) {
            return new TenantAdministratorInitializedEventFactory(
                    objectMapper, ids, "saas.forge.test.tenant-access-service.events");
        }

        @Bean
        CreatePendingTenantService service(
                MyBatisTenantRepository tenants,
                MyBatisTenantCreationIdempotency idempotency,
                OutboxEventRepository outbox,
                TenantCreatedEventFactory events,
                UuidV7Generator ids,
                Clock clock) {
            return new CreatePendingTenantService(tenants, idempotency, outbox, events, ids, clock);
        }

        @Bean
        InitialSubscriptionEligibilityService eligibility(
                MyBatisTenantRepository tenants, Clock clock) {
            return new InitialSubscriptionEligibilityService(tenants, clock);
        }
    }

    static final class ScriptedSessionRevocationGateway implements SessionRevocationGateway {
        private final Deque<Result> results = new ArrayDeque<>();
        private final Deque<RuntimeException> failures = new ArrayDeque<>();
        private UUID lastRevocationRequestId;
        private UUID lastRecoveredRevocationRequestId;
        private UUID lastReleasedRevocationRequestId;

        @Override
        public Result revoke(UUID revocationRequestId, UUID tenantId) {
            lastRevocationRequestId = revocationRequestId;
            if (!failures.isEmpty()) throw failures.removeFirst();
            return results.isEmpty() ? Result.completed(0, 0) : results.removeFirst();
        }

        @Override
        public void recover(UUID revocationRequestId, UUID tenantId) {
            lastRecoveredRevocationRequestId = revocationRequestId;
        }

        @Override
        public void release(UUID releaseRequestId, UUID revocationRequestId, UUID tenantId) {
            lastReleasedRevocationRequestId = revocationRequestId;
        }

        void reset() {
            results.clear();
            failures.clear();
            lastRevocationRequestId = null;
            lastRecoveredRevocationRequestId = null;
            lastReleasedRevocationRequestId = null;
        }
    }
}
