package io.saasforge.sdk.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.MqAutoConfiguration;
import io.saasforge.sdk.tenant.UuidTypeHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

class NotificationClientTest {
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static {
        postgres.start();
    }

    static DriverManagerDataSource dataSource;

    @BeforeAll
    static void initializeDatabase() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("io/saasforge/sdk/mq/schema.sql")).execute(dataSource);
    }

    final UUID tenant = UUID.randomUUID();
    ApplicationContextRunner context;
    TransactionTemplate transaction;
    PublishedEvents events;

    @BeforeEach
    void setUp() throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("reset.sql")).execute(dataSource);
        var configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register(UUID.class, new UuidTypeHandler());
        configuration.addMapper(PublishedEvents.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        events = session.getMapper(PublishedEvents.class);
        PlatformTransactionManager manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        context = new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(MqAutoConfiguration.class, NotificationAutoConfiguration.class))
                .withBean(SqlSessionTemplate.class, () -> session)
                .withBean(PlatformTransactionManager.class, () -> manager)
                .withPropertyValues("forge.mq.enabled=false", "spring.application.name=orders");
    }

    @Test
    void committedNotificationPublishesTenantTargetContentAndLink() {
        UUID membership = UUID.randomUUID();
        context.run(app -> CurrentTenant.run(tenant, () -> {
            UUID id = transaction.execute(status -> app.getBean(NotificationClient.class)
                    .send(
                            NotificationTarget.memberships(List.of(membership)),
                            "订单已提交",
                            "订单 42 已提交",
                            new NotificationLink("orders", "/orders/42?tab=detail#items")));
            var event = events.all().get(0);
            assertThat(event.topic()).isEqualTo("forge-notification");
            assertThat(event.tag()).isEqualTo("NotificationSent");
            assertThat(event.tenantId()).isEqualTo(tenant);
            var sent = JsonMapper.builder().build().readValue(event.payload(), NotificationSent.class);
            assertThat(sent.id()).isEqualTo(id);
            assertThat(sent.tenantId()).isEqualTo(tenant);
            assertThat(sent.service()).isEqualTo("orders");
            assertThat(sent.target().membershipIds()).containsExactly(membership);
            assertThat(sent.title()).isEqualTo("订单已提交");
            assertThat(sent.body()).isEqualTo("订单 42 已提交");
            assertThat(sent.link()).isEqualTo(new NotificationLink("orders", "/orders/42?tab=detail#items"));
            assertThat(sent.occurredAt()).isNotNull();
        }));
    }

    @Test
    void rolledBackBusinessOperationPublishesNoNotification() {
        context.run(app -> CurrentTenant.run(tenant, () -> {
            transaction.executeWithoutResult(status -> {
                app.getBean(NotificationClient.class)
                        .send(NotificationTarget.permission("order:read"), "订单已提交", "订单 42 已提交", null);
                status.setRollbackOnly();
            });
            assertThat(events.all()).isEmpty();
        }));
    }

    @Test
    void notificationRejectsAnOversizedTitleBeforePublishing() {
        context.run(app -> CurrentTenant.run(tenant, () -> {
            transaction.executeWithoutResult(status -> assertThatThrownBy(() -> app.getBean(NotificationClient.class)
                            .send(NotificationTarget.permission("order:read"), "题".repeat(201), "订单已提交", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("title"));
            assertThat(events.all()).isEmpty();
        }));
    }

    @Test
    void todoEventsPreserveBusinessKeyAndOccurrenceTimeWithoutAssumingDeliveryOrder() {
        Instant openedAt = Instant.parse("2026-11-07T01:00:00Z");
        Instant closedAt = Instant.parse("2026-11-07T02:00:00Z");
        context.run(app -> CurrentTenant.run(tenant, () -> {
            var client = app.getBean(NotificationClient.class);
            transaction.executeWithoutResult(status -> {
                client.closeTodo("order:42:approval", closedAt);
                client.openTodo(
                        "order:42:approval",
                        NotificationTarget.permission("order:approve"),
                        "审核订单",
                        "订单 42 等待审核",
                        new NotificationLink("orders", "/orders/42"),
                        openedAt);
            });
            var published = events.all();
            assertThat(published).hasSize(2).allSatisfy(event -> {
                assertThat(event.topic()).isEqualTo("forge-notification");
                assertThat(event.tenantId()).isEqualTo(tenant);
            });
            var json = JsonMapper.builder().build();
            var closed = json.readValue(published.get(0).payload(), TodoClosed.class);
            var opened = json.readValue(published.get(1).payload(), TodoOpened.class);
            assertThat(published.get(0).tag()).isEqualTo("TodoClosed");
            assertThat(closed).isEqualTo(new TodoClosed(tenant, "orders", "order:42:approval", closedAt));
            assertThat(published.get(1).tag()).isEqualTo("TodoOpened");
            assertThat(opened)
                    .isEqualTo(new TodoOpened(
                            tenant,
                            "orders",
                            "order:42:approval",
                            NotificationTarget.permission("order:approve"),
                            "审核订单",
                            "订单 42 等待审核",
                            new NotificationLink("orders", "/orders/42"),
                            openedAt));
        }));
    }

    @Test
    void aTargetMustChooseExactlyOneRecipientMode() {
        assertThatThrownBy(() -> new NotificationTarget(List.of(UUID.randomUUID()), "order:read"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.com/path",
                "//example.com/path",
                "javascript:alert(1)",
                "relative/path",
                "/bad path",
                "/path\\evil"
            })
    void linksRejectExternalUrlsAndMalformedRoutes(String route) {
        assertThatThrownBy(() -> new NotificationLink("orders", route))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("route");
    }

    @Test
    void blankSourceServicePreventsStartup() {
        context.withPropertyValues("spring.application.name=")
                .run(app -> assertThat(app).hasFailed());
    }

    @Test
    void allEntryPointsRequireATransactionAndAnActiveTenant() {
        context.run(app -> {
            var client = app.getBean(NotificationClient.class);
            Instant occurredAt = Instant.parse("2026-11-07T01:00:00Z");
            CurrentTenant.run(tenant, () -> {
                assertThatThrownBy(() -> client.openTodo(
                                "order:42",
                                NotificationTarget.permission("order:approve"),
                                "审核订单",
                                "等待审核",
                                null,
                                occurredAt))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("transaction");
                assertThatThrownBy(() -> client.closeTodo("order:42", occurredAt))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("transaction");
            });
            transaction.executeWithoutResult(status -> assertThatThrownBy(
                            () -> client.send(NotificationTarget.permission("order:read"), "订单已提交", "请查收", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tenant"));
            assertThat(events.all()).isEmpty();
        });
    }

    @Test
    void todoOpenAndCloseRollBackWithBusinessData() {
        context.run(app -> CurrentTenant.run(tenant, () -> {
            transaction.executeWithoutResult(status -> {
                var client = app.getBean(NotificationClient.class);
                client.openTodo(
                        "order:42",
                        NotificationTarget.permission("order:approve"),
                        "审核订单",
                        "等待审核",
                        null,
                        Instant.parse("2026-11-07T01:00:00Z"));
                client.closeTodo("order:42", Instant.parse("2026-11-07T02:00:00Z"));
                status.setRollbackOnly();
            });
            assertThat(events.all()).isEmpty();
        }));
    }

    @Test
    void contentLengthLimitsAcceptTheirDocumentedBoundaries() {
        context.run(app -> CurrentTenant.run(tenant, () -> {
            transaction.executeWithoutResult(status -> app.getBean(NotificationClient.class)
                    .send(NotificationTarget.permission("order:read"), "题".repeat(200), "文".repeat(10000), null));
            assertThat(events.all()).hasSize(1);
        }));
    }

    @Test
    void oversizedBodyAndTodoKeysNeverReachTheOutbox() {
        context.run(app -> CurrentTenant.run(tenant, () -> {
            var client = app.getBean(NotificationClient.class);
            transaction.executeWithoutResult(status -> {
                assertThatThrownBy(() ->
                                client.send(NotificationTarget.permission("order:read"), "订单", "文".repeat(10001), null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("body");
                assertThatThrownBy(() -> client.closeTodo("k".repeat(256), Instant.now()))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("businessKey");
                assertThatThrownBy(() -> client.openTodo(
                                "order:42", NotificationTarget.permission("order:approve"), "审核", "等待审核", null, null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("occurredAt");
            });
            assertThat(events.all()).isEmpty();
        }));
    }

    @Test
    void targetsAreImmutableAndMustIdentifyRecipients() {
        var memberships = new ArrayList<UUID>();
        UUID membership = UUID.randomUUID();
        memberships.add(membership);
        var target = NotificationTarget.memberships(memberships);
        memberships.clear();
        assertThat(target.membershipIds()).containsExactly(membership);
        assertThatThrownBy(() -> NotificationTarget.memberships(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationTarget.permission(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationTarget.permission("p".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linkLimitsFollowAppRegistryAndRouteBounds() {
        assertThatThrownBy(() -> new NotificationLink("Invalid App", "/orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appCode");
        assertThatThrownBy(() -> new NotificationLink("a".repeat(65), "/orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appCode");
        assertThatThrownBy(() -> new NotificationLink("orders", "/" + "x".repeat(2048)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("route");
    }

    @Test
    void sendingOutsideBusinessTransactionFailsExplicitly() {
        context.run(app -> CurrentTenant.run(
                tenant,
                () -> assertThatThrownBy(() -> app.getBean(NotificationClient.class)
                                .send(
                                        NotificationTarget.memberships(List.of(UUID.randomUUID())),
                                        "订单已提交",
                                        "订单已提交，请查收",
                                        new NotificationLink("orders", "/orders/42")))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("transaction")));
    }
}
