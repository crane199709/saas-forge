package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.iam.verification.service.ContactMessage;
import io.saasforge.iam.verification.service.EmailSender;
import io.saasforge.iam.verification.service.InvitationMessage;
import io.saasforge.iam.verification.service.SmsSender;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantInitializationReported;
import io.saasforge.sdk.provisioning.TenantInitializationRequested;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * iam 消费 TenantCreated 开通初始管理员（工单 14）：已有 Identity 收到邀请，没有的建立待激活 Identity；
 * 接受邀请后成为该租户 active 的 Owner。重复初始化不产生重复数据，邀请发送失败时回报失败并可重试。
 *
 * <p>按消费端的方式调用 SDK 注册的事件处理器（恢复租户上下文并开启事务），不经 RocketMQ。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
class TenantProvisioningTest {
    static final String SERVICE = "forge-iam";
    static final String PASSWORD = "Invitee-password-123!";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE = new ParameterizedTypeReference<>() {};

    static final SharedContainers.Postgres postgres = SharedContainers.postgres();

    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
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
        private final Map<String, InvitationMessage> latest = new ConcurrentHashMap<>();
        private final Map<String, Integer> received = new ConcurrentHashMap<>();
        // 模拟发送通道故障：发往这些目标的消息失败
        final Set<String> broken = ConcurrentHashMap.newKeySet();

        void deliver(String target, ContactMessage message) {
            if (broken.contains(target)) throw new IllegalStateException("channel down");
            if (message instanceof InvitationMessage invitation) {
                latest.put(target, invitation);
                received.merge(target, 1, Integer::sum);
            }
        }

        InvitationMessage invitation(String target) {
            return latest.get(target);
        }

        String token(String target) {
            return UriComponentsBuilder.fromUriString(latest.get(target).acceptUrl())
                    .build()
                    .getQueryParams()
                    .getFirst("invitation");
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
    OutboxAuditMapper outbox;

    @Autowired
    MembershipService memberships;

    @Autowired
    RoleService roles;

    @Test
    void existingIdentityIsInvitedAsOwnerAndJoinsAfterAccepting() {
        var tenant = new TenantCreated(UUID.randomUUID(), "邀请已有身份", "Demo@Example.com", "active");

        deliver(tenant.tenantId(), tenant);
        // 同一租户重复初始化（如重试）：不产生第二个 Membership 或预置角色
        deliver(tenant.tenantId(), tenant);

        assertThat(memberships.count(tenant.tenantId())).isEqualTo(1);
        assertThat(reports(tenant.tenantId()))
                .containsExactly(
                        new TenantInitializationReported(tenant.tenantId(), SERVICE, true, null),
                        new TenantInitializationReported(tenant.tenantId(), SERVICE, true, null));
        InvitationMessage invitation = inbox.invitation("Demo@Example.com");
        assertThat(invitation.tenantName()).isEqualTo("邀请已有身份");
        assertThat(invitation.requiresCredentials()).isFalse();
        assertThat(invitation.acceptUrl()).startsWith("http://localhost:5174/invitations/accept?invitation=");
        // invited 的 Membership 不能进入
        assertThat(tenantIdsOf(login("demo", "Demo-password-123!"))).doesNotContain(tenant.tenantId());

        String token = inbox.token("Demo@Example.com");
        Map<String, Object> preview = post("/auth/invitations/preview", Map.of("token", token));
        assertThat(data(preview)).containsEntry("tenantName", "邀请已有身份").containsEntry("requiresCredentials", false);
        Map<String, Object> accepted = post("/auth/invitations/accept", Map.of("token", token));
        assertThat(accepted.get("httpStatus")).isEqualTo(200);
        assertThat(data(accepted)).containsEntry("tenantId", tenant.tenantId().toString());

        Map<String, Object> login = login("demo", "Demo-password-123!");
        assertThat(tenantIdsOf(login)).contains(tenant.tenantId());
        assertThat(roles.grantsOf(membershipIn(login, tenant.tenantId())).roles())
                .contains(RoleService.OWNER);
        // 邀请一次性有效；已接受后重试初始化不再发邀请
        assertThat(post("/auth/invitations/accept", Map.of("token", token)).get("code"))
                .isEqualTo("invitation.invalid");
        deliver(tenant.tenantId(), new TenantInitializationRequested(SERVICE, tenant));
        assertThat(inbox.received.get("Demo@Example.com")).isEqualTo(2);
    }

    @Test
    void newContactGetsPendingIdentityThatSetsCredentialsWhenAccepting() {
        var tenant = new TenantCreated(UUID.randomUUID(), "邀请新身份", "13700000001", "active");

        deliver(tenant.tenantId(), tenant);

        assertThat(inbox.invitation("13700000001").requiresCredentials()).isTrue();
        String token = inbox.token("13700000001");
        assertThat(data(post("/auth/invitations/preview", Map.of("token", token))))
                .containsEntry("requiresCredentials", true);
        // 待激活身份不能登录，也不能不设凭证就接受
        assertThat(post("/auth/invitations/accept", Map.of("token", token)).get("code"))
                .isEqualTo("invitation.credentialsRequired");
        assertThat(post("/auth/invitations/accept", Map.of("token", token, "account", "demo", "password", PASSWORD))
                        .get("code"))
                .isEqualTo("registration.accountTaken");

        Map<String, Object> accepted = post(
                "/auth/invitations/accept",
                Map.of("token", token, "account", "invitee", "password", PASSWORD, "displayName", "新管理员"));
        assertThat(accepted.get("httpStatus")).isEqualTo(200);

        Map<String, Object> login = login("invitee", PASSWORD);
        assertThat(tenantIdsOf(login)).containsExactly(tenant.tenantId());
        assertThat(roles.grantsOf(membershipIn(login, tenant.tenantId())).roles())
                .contains(RoleService.OWNER);
    }

    @Test
    void failedInvitationIsRolledBackAndReportedThenRetrySucceeds() {
        var tenant = new TenantCreated(UUID.randomUUID(), "发送失败", "broken-admin@example.com", "active");
        inbox.broken.add("broken-admin@example.com");

        deliver(tenant.tenantId(), tenant);

        assertThat(memberships.count(tenant.tenantId())).isZero();
        List<TenantInitializationReported> failed = reports(tenant.tenantId());
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).succeeded()).isFalse();
        assertThat(failed.get(0).error()).contains("channel down");

        inbox.broken.clear();
        // 发给其他服务的重试请求不触发 iam 初始化
        deliver(tenant.tenantId(), new TenantInitializationRequested("demo-service", tenant));
        assertThat(reports(tenant.tenantId())).hasSize(1);
        deliver(tenant.tenantId(), new TenantInitializationRequested(SERVICE, tenant));

        assertThat(memberships.count(tenant.tenantId())).isEqualTo(1);
        assertThat(reports(tenant.tenantId()))
                .last()
                .isEqualTo(new TenantInitializationReported(tenant.tenantId(), SERVICE, true, null));
        assertThat(inbox.invitation("broken-admin@example.com")).isNotNull();
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

    private List<TenantInitializationReported> reports(UUID tenantId) {
        return outbox.payloads(tenantId, TenantInitializationReported.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, TenantInitializationReported.class))
                .toList();
    }

    private Map<String, Object> login(String account, String password) {
        return post("/auth/login", Map.of("account", account, "password", password));
    }

    private static List<UUID> tenantIdsOf(Map<String, Object> login) {
        return memberships(login).stream()
                .map(membership -> UUID.fromString((String) membership.get("tenantId")))
                .toList();
    }

    private static UUID membershipIn(Map<String, Object> login, UUID tenantId) {
        return memberships(login).stream()
                .filter(membership -> tenantId.toString().equals(membership.get("tenantId")))
                .map(membership -> UUID.fromString((String) membership.get("membershipId")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> memberships(Map<String, Object> login) {
        return (List<Map<String, Object>>) data(login).get("memberships");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("data");
    }

    private Map<String, Object> post(String path, Object body) {
        return RestClient.create("http://127.0.0.1:" + port)
                .post()
                .uri(path)
                .body(new HashMap<>((Map<?, ?>) body))
                .exchange((request, response) -> response.bodyTo(ENVELOPE));
    }
}
