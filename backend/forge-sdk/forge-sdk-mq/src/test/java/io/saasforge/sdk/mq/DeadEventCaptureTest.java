package io.saasforge.sdk.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.tenant.UuidTypeHandler;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 直接投递 MQ 消息到消费入口，用真实 PostgreSQL 验证事务边界与幂等结果。 */
class DeadEventCaptureTest {
    static final SharedContainers.Database postgres = SharedContainers.database();

    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    TransactionTemplate transaction;
    ConsumedMessageMapper consumed;
    CaptureProbe probe;
    SqlSessionTemplate session;
    EventConsumer consumer;
    final UUID tenant = UUID.randomUUID();
    final List<UUID> handledTenants = new ArrayList<>();
    boolean failing = true;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(
                        new ClassPathResource("capture-reset.sql"),
                        new ClassPathResource("io/saasforge/sdk/mq/schema.sql"),
                        new ClassPathResource("capture-fixture.sql"))
                .execute(dataSource);
        var configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register(UUID.class, new UuidTypeHandler());
        configuration.addMapper(ConsumedMessageMapper.class);
        configuration.addMapper(CaptureProbe.class);
        configuration.addMapper(DeadEventMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        session = new SqlSessionTemplate(factory.getObject());
        consumed = session.getMapper(ConsumedMessageMapper.class);
        probe = session.getMapper(CaptureProbe.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        consumer = new EventConsumer(
                null,
                "capture-test",
                List.of(new FailingHandler()),
                consumed,
                transaction,
                JsonMapper.builder().build(),
                session.getMapper(DeadEventMapper.class),
                16,
                metrics);
    }

    @Test
    void exhaustedDeliveryIsAcknowledgedAfterBusinessRollback() {
        assertThat(consumer.consume(message("exhausted", 16, "{\"value\":\"example\"}")))
                .isEqualTo(ConsumeResult.SUCCESS);
        assertThat(probe.businessWrites()).isZero();
        assertThat(probe.deadEvents()).isEqualTo(1);
        assertThat(handledTenants).containsExactly(tenant);
        assertThat(CurrentTenant.find()).isEmpty();
    }

    @Test
    void deliveryBeforeTheLimitIsRetried() {
        assertThat(consumer.consume(message("retry", 15, "{\"value\":\"example\"}")))
                .isEqualTo(ConsumeResult.FAILURE);
        assertThat(probe.businessWrites()).isZero();
        assertThat(probe.deadEvents()).isZero();
    }

    @Test
    void duplicateCaptureCountsOneNewDeadEvent() {
        MessageView message = message("duplicate", 16, "{\"value\":\"example\"}");
        assertThat(consumer.consume(message)).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(consumer.consume(message)).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(probe.deadEvents()).isEqualTo(1);
        assertThat(metrics.get("forge.mq.dead.events")
                        .tag("consumer.group", "capture-test")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void outboxBacklogRemainsObservableWhileMqIsDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MqAutoConfiguration.class))
                .withBean(SqlSessionTemplate.class, () -> session)
                .withBean(PlatformTransactionManager.class, () -> transaction.getTransactionManager())
                .withBean(MeterRegistry.class, () -> metrics)
                .withPropertyValues("forge.mq.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(metrics.get("forge.mq.outbox.pending").gauge().value())
                            .isZero();
                    var publisher = context.getBean(EventPublisher.class);
                    transaction.executeWithoutResult(
                            status -> publisher.publish("capture-topic", new Example("queued")));
                    assertThat(metrics.get("forge.mq.outbox.pending").gauge().value())
                            .isEqualTo(1);
                });
    }

    @Test
    void captureFailureDoesNotAcknowledgeOrCountTheEvent() {
        assertThat(consumer.consume(message("write-fails", 16, "{\"value\":\"example\"}")))
                .isEqualTo(ConsumeResult.FAILURE);
        assertThat(probe.businessWrites()).isZero();
        assertThat(probe.deadEvents()).isZero();
        assertThat(metrics.get("forge.mq.dead.events").counter().count()).isZero();
    }

    @Test
    void invalidJsonIsCapturedWithoutChangingTheOriginalBody() {
        String payload = "not valid JSON";
        MessageView message = message("invalid-json", 16, payload);
        assertThat(consumer.consume(message)).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(probe.deadPayload().payload()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(message.getBody().remaining()).isEqualTo(payload.length());
        assertThat(probe.businessWrites()).isZero();
    }

    @Test
    void malformedTenantHeaderIsCapturedAndRemainsInvalidForReplay() {
        MessageView message = message("invalid-tenant", 16, "{\"value\":\"example\"}");
        when(message.getProperties())
                .thenReturn(Map.of(
                        EventPublisher.EVENT_ID_PROPERTY,
                        "invalid-tenant",
                        EventPublisher.TENANT_ID_PROPERTY,
                        "invalid"));
        assertThat(consumer.consume(message)).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(probe.originalTenantId()).isEqualTo("invalid");
        assertThatThrownBy(() -> consumer.handle(
                        "capture-test",
                        "invalid-tenant",
                        probe.originalTenantId(),
                        "capture-topic",
                        "Example",
                        probe.deadPayload().payload()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(handledTenants).isEmpty();
    }

    @Test
    void independentlyCommittedCaptureSurvivesCallerRollback() {
        transaction.executeWithoutResult(status -> {
            assertThat(consumer.consume(message("outer-rollback", 16, "{\"value\":\"example\"}")))
                    .isEqualTo(ConsumeResult.SUCCESS);
            status.setRollbackOnly();
        });
        assertThat(probe.deadEvents()).isEqualTo(1);
        assertThat(probe.businessWrites()).isZero();
    }

    @Test
    void successfulConsumerAndReplayShareDeduplicationAndRestoreTenant() {
        failing = false;
        UUID otherTenant = UUID.randomUUID();
        byte[] payload = "{\"value\":\"example\"}".getBytes(StandardCharsets.UTF_8);
        CurrentTenant.run(otherTenant, () -> {
            assertThat(consumer.consume(message("success", 1, new String(payload, StandardCharsets.UTF_8))))
                    .isEqualTo(ConsumeResult.SUCCESS);
            consumer.handle("capture-test", "success", tenant.toString(), "capture-topic", "Example", payload);
            consumer.handle("another-group", "success", otherTenant.toString(), "capture-topic", "Example", payload);
            assertThat(CurrentTenant.require()).isEqualTo(otherTenant);
        });
        assertThat(probe.businessWrites()).isEqualTo(2);
        assertThat(handledTenants).containsExactly(tenant, otherTenant);
        assertThat(probe.deadEvents()).isZero();
        assertThat(CurrentTenant.find()).isEmpty();
    }

    @Test
    void missingHandlerIsSkippedForMqButFailsReplay() {
        MessageView message = message("missing-handler", 16, "{}");
        when(message.getTag()).thenReturn(Optional.of("Removed"));
        assertThat(consumer.consume(message)).isEqualTo(ConsumeResult.SUCCESS);
        assertThatThrownBy(() -> consumer.handle(
                        "capture-test", "missing-handler", tenant.toString(), "capture-topic", "Removed", new byte[0]))
                .isInstanceOf(IllegalStateException.class);
        assertThat(probe.deadEvents()).isZero();
    }

    @Test
    void retryLimitBindsDefaultAndRejectsZero() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MqAutoConfiguration.class))
                .withBean(SqlSessionTemplate.class, () -> session)
                .withBean(PlatformTransactionManager.class, () -> transaction.getTransactionManager())
                .withPropertyValues("forge.mq.enabled=false");
        runner.run(context -> assertThat(context.getBean(MqProperties.class).maxDeliveryAttempts())
                .isEqualTo(16));
        runner.withPropertyValues("forge.mq.max-delivery-attempts=3")
                .run(context -> assertThat(context.getBean(MqProperties.class).maxDeliveryAttempts())
                        .isEqualTo(3));
        runner.withPropertyValues("forge.mq.max-delivery-attempts=0")
                .run(context -> assertThat(context).hasFailed());
    }

    MessageView message(String eventId, int attempt, String payload) {
        var message = mock(MessageView.class);
        var messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn("broker-message");
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getTopic()).thenReturn("capture-topic");
        when(message.getTag()).thenReturn(Optional.of("Example"));
        when(message.getProperties())
                .thenReturn(Map.of(
                        EventPublisher.EVENT_ID_PROPERTY,
                        eventId,
                        EventPublisher.TENANT_ID_PROPERTY,
                        tenant.toString()));
        when(message.getDeliveryAttempt()).thenReturn(attempt);
        when(message.getBody())
                .thenReturn(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8))
                        .asReadOnlyBuffer());
        return message;
    }

    record Example(String value) {}

    class FailingHandler implements EventHandler<Example> {
        @Override
        public String topic() {
            return "capture-topic";
        }

        @Override
        public Class<Example> eventType() {
            return Example.class;
        }

        @Override
        public void handle(Example event) {
            handledTenants.add(CurrentTenant.require());
            probe.writeBusiness();
            if (failing) {
                throw new IllegalStateException("expected handler failure");
            }
        }
    }
}
