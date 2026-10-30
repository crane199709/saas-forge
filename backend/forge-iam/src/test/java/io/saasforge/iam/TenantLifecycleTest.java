package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.verification.service.ContactMessage;
import io.saasforge.iam.verification.service.EmailSender;
import io.saasforge.iam.verification.service.ExpiryReminderMessage;
import io.saasforge.iam.verification.service.InvitationMessage;
import io.saasforge.iam.verification.service.SmsSender;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantExpiryApproached;
import io.saasforge.sdk.provisioning.TenantStatusChanged;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * iam 消费 Tenant Lifecycle 事件（工单 15）：按版本应用状态变更并更新网关读取的 Redis 状态，试用租户可登录，
 * 到期提醒经邮件/短信 SPI 发给 Owner。
 *
 * <p>按消费端的方式调用 SDK 注册的事件处理器（恢复租户上下文并开启事务），不经 RocketMQ。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
class TenantLifecycleTest {
    static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE = new ParameterizedTypeReference<>() {};

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../deploy/postgres/init"), "/docker-entrypoint-initdb.d/");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("forge.iam.client-secret", () -> "test-client-secret");
        // 演示数据提供已有凭证的 Identity：demo / demo@example.com
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> "target/test-signing.jwk");
    }

    @TestConfiguration
    static class CapturingSenders {
        @Bean
        Inbox inbox() {
            return new Inbox();
        }

        @Bean
        SmsSender smsSender(Inbox inbox) {
            return inbox::deliver;
        }

        @Bean
        EmailSender emailSender(Inbox inbox) {
            return inbox::deliver;
        }
    }

    static class Inbox {
        final Map<String, List<ContactMessage>> received = new ConcurrentHashMap<>();

        void deliver(String target, ContactMessage message) {
            received.computeIfAbsent(target, ignored -> new CopyOnWriteArrayList<>())
                    .add(message);
        }

        <T extends ContactMessage> List<T> of(String target, Class<T> type) {
            return received.getOrDefault(target, List.of()).stream()
                    .filter(type::isInstance)
                    .map(type::cast)
                    .toList();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    Inbox inbox;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void trialTenantIsAccessibleAndStatusChangesApplyInVersionOrder() {
        var tenant = new TenantCreated(UUID.randomUUID(), "试用租户", "Demo@Example.com", "trial");
        deliver(tenant.tenantId(), tenant);
        assertThat(post("/auth/invitations/accept", Map.of("token", invitationToken("Demo@Example.com")))
                        .get("httpStatus"))
                .isEqualTo(200);
        // 试用租户与正常租户一样可以进入
        assertThat(tenantIdsOf(login())).contains(tenant.tenantId());

        deliver(tenant.tenantId(), changed(tenant, "trial", "suspended", 1));

        assertThat(status(tenant.tenantId())).isEqualTo("suspended");
        assertThat(tenantIdsOf(login())).doesNotContain(tenant.tenantId());

        // 乱序或重复送达的旧版本不覆盖已应用的新状态
        deliver(tenant.tenantId(), changed(tenant, "pending", "trial", 0));
        deliver(tenant.tenantId(), changed(tenant, "trial", "suspended", 1));
        assertThat(status(tenant.tenantId())).isEqualTo("suspended");

        deliver(tenant.tenantId(), changed(tenant, "suspended", "active", 2));
        assertThat(status(tenant.tenantId())).isEqualTo("active");
        assertThat(tenantIdsOf(login())).contains(tenant.tenantId());
    }

    @Test
    void statusChangeOfTenantNotYetRegisteredTakesEffect() {
        // iam 的初始化失败或尚未处理时，停用仍要先生效
        var tenant = new TenantCreated(UUID.randomUUID(), "未登记租户", "nobody@example.com", "active");

        deliver(tenant.tenantId(), changed(tenant, "active", "suspended", 1));

        assertThat(status(tenant.tenantId())).isEqualTo("suspended");
    }

    @Test
    void expiryReminderIsSentToOwner() {
        var tenant = new TenantCreated(UUID.randomUUID(), "即将到期", "owner-expiry@example.com", "trial");
        deliver(tenant.tenantId(), tenant);
        var expiresAt = OffsetDateTime.of(2030, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        deliver(tenant.tenantId(), new TenantExpiryApproached(tenant.tenantId(), tenant.name(), "trial", expiresAt));

        // 初始管理员尚未接受邀请也是 Owner，照常提醒
        assertThat(inbox.of("owner-expiry@example.com", ExpiryReminderMessage.class))
                .containsExactly(new ExpiryReminderMessage("即将到期", true, expiresAt));
    }

    private static TenantStatusChanged changed(TenantCreated tenant, String from, String to, long version) {
        return new TenantStatusChanged(tenant.tenantId(), tenant.name(), from, to, version);
    }

    private String status(UUID tenantId) {
        return redisTemplate.opsForValue().get(MembershipStateKeys.tenantStatus(tenantId));
    }

    /** 与 SDK 消费端一致：先恢复发布方的租户，再在事务中调用处理器。 */
    @SuppressWarnings("unchecked")
    private <T> void deliver(UUID tenantId, T event) {
        var handler = (EventHandler<T>) handlers.stream()
                .filter(candidate -> candidate.eventType() == event.getClass())
                .findFirst()
                .orElseThrow();
        CurrentTenant.run(tenantId, () -> transaction.executeWithoutResult(status -> handler.handle(event)));
    }

    private String invitationToken(String target) {
        List<InvitationMessage> invitations = inbox.of(target, InvitationMessage.class);
        return UriComponentsBuilder.fromUriString(
                        invitations.get(invitations.size() - 1).acceptUrl())
                .build()
                .getQueryParams()
                .getFirst("invitation");
    }

    private Map<String, Object> login() {
        return post("/auth/login", Map.of("account", "demo", "password", "Demo-password-123!"));
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> tenantIdsOf(Map<String, Object> login) {
        var data = (Map<String, Object>) login.get("data");
        return ((List<Map<String, Object>>) data.get("memberships"))
                .stream()
                        .map(membership -> UUID.fromString((String) membership.get("tenantId")))
                        .toList();
    }

    private Map<String, Object> post(String path, Object body) {
        return RestClient.create("http://127.0.0.1:" + port)
                .post()
                .uri(path)
                .body(new HashMap<>((Map<?, ?>) body))
                .exchange((request, response) -> response.bodyTo(ENVELOPE));
    }
}
