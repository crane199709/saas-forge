package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.verification.service.EmailSender;
import io.saasforge.iam.verification.service.InvitationMessage;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantDeleted;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** 公告通过两端 HTTP 入口发布、读取与撤回，已读状态按 Membership 隔离。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql({"/fixtures/organization.sql", "/fixtures/announcements.sql"})
@SuppressWarnings("unchecked")
class AnnouncementTest {
    static final String KEY_FILE = "target/test-announcement-signing.jwk";
    static final String PLATFORM_KEY_FILE = "target/test-announcement-platform-signing.jwk";
    static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-0000000000b1");
    static final UUID ADMIN = UUID.fromString("019a0110-0000-7000-8000-0000000000b3");
    static final UUID MEMBER = UUID.fromString("019a0110-0000-7000-8000-0000000000b5");
    static final UUID ADMIN_B = UUID.fromString("019a0110-0000-7000-8000-0000000000b7");
    static final Map<UUID, UUID> IDENTITIES = Map.of(
            ADMIN, UUID.fromString("019a0110-0000-7000-8000-0000000000b2"),
            MEMBER, UUID.fromString("019a0110-0000-7000-8000-0000000000b4"),
            ADMIN_B, UUID.fromString("019a0110-0000-7000-8000-0000000000b6"));
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> KEY_FILE);
        registry.add("forge.iam.platform.signing-key-file", () -> PLATFORM_KEY_FILE);
    }

    @TestConfiguration
    static class Delivery {
        @Bean
        AtomicReference<InvitationMessage> invitations() {
            return new AtomicReference<>();
        }

        @Bean
        EmailSender emailSender(AtomicReference<InvitationMessage> invitations) {
            return (target, message) -> {
                if (message instanceof InvitationMessage invitation) invitations.set(invitation);
            };
        }
    }

    @Autowired
    AtomicReference<InvitationMessage> invitations;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    TransactionTemplate transactions;

    @MockitoSpyBean
    EventPublisher publisher;

    @LocalServerPort
    int port;

    @Test
    void tenantAnnouncementIsVisibleOnlyInItsTenantAndReadStateBelongsToTheMembership() throws Exception {
        String admin = token(ADMIN);
        String member = token(MEMBER);
        String other = token(ADMIN_B);
        long before = unread(member);
        var published = call(admin, HttpMethod.POST, "/announcements/manage", Map.of("title", "维护安排", "body", "周日维护"));
        assertThat(published.getStatusCode().value()).isEqualTo(201);
        String id = (String) data(published).get("id");
        assertThat(list(call(member, HttpMethod.GET, "/announcements", null)))
                .anySatisfy(item -> assertThat(item).containsEntry("id", id).containsEntry("read", false));
        assertThat(unread(member)).isEqualTo(before + 1);
        assertThat(list(call(other, HttpMethod.GET, "/announcements", null)))
                .noneSatisfy(item -> assertThat(item).containsEntry("id", id));
        assertThat(call(other, HttpMethod.POST, "/announcements/" + id + "/read", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(call(member, HttpMethod.POST, "/announcements/" + id + "/read", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(member, HttpMethod.POST, "/announcements/" + id + "/read", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(unread(member)).isEqualTo(before);
        assertThat(list(call(admin, HttpMethod.GET, "/announcements", null)))
                .anySatisfy(item -> assertThat(item).containsEntry("id", id).containsEntry("read", false));
    }

    @Test
    void platformAnnouncementsReachAllOrSelectedTenantsAndTheTwoRealmPermissionsStaySeparate() throws Exception {
        String operator = platformToken();
        String admin = token(ADMIN);
        String other = token(ADMIN_B);
        var global = call(
                operator,
                HttpMethod.POST,
                "/platform/announcements",
                Map.of("title", "全平台公告", "body", "所有租户可读", "allTenants", true));
        assertThat(global.getStatusCode().value()).isEqualTo(201);
        String globalId = (String) data(global).get("id");
        var selected = call(
                operator,
                HttpMethod.POST,
                "/platform/announcements",
                Map.of("title", "指定租户公告", "body", "只给 A", "allTenants", false, "tenantIds", List.of(TENANT)));
        assertThat(selected.getStatusCode().value()).isEqualTo(201);
        String selectedId = (String) data(selected).get("id");
        assertThat(list(call(admin, HttpMethod.GET, "/announcements", null)))
                .extracting(item -> item.get("id"))
                .contains(globalId, selectedId);
        assertThat(list(call(other, HttpMethod.GET, "/announcements", null)))
                .extracting(item -> item.get("id"))
                .contains(globalId)
                .doesNotContain(selectedId);
        assertThat(list(call(operator, HttpMethod.GET, "/platform/announcements", null)))
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("id", selectedId)
                        .containsEntry("tenantIds", List.of(TENANT.toString())));
        assertThat(call(
                                admin,
                                HttpMethod.POST,
                                "/platform/announcements",
                                Map.of("title", "越权", "body", "拒绝", "allTenants", true))
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(call(operator, HttpMethod.POST, "/announcements/manage", Map.of("title", "越权", "body", "拒绝"))
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(call(token(MEMBER), HttpMethod.POST, "/announcements/manage", Map.of("title", "越权", "body", "拒绝"))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(call(null, HttpMethod.GET, "/announcements", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
    }

    @Test
    void revokedAndExpiredAnnouncementsDisappearButManagementAndAuditKeepThePublishedFacts() throws Exception {
        String admin = token(ADMIN);
        String member = token(MEMBER);
        String operator = platformToken();
        var published = call(admin, HttpMethod.POST, "/announcements/manage", Map.of("title", "撤回测试", "body", "原内容"));
        assertThat(published.getStatusCode().value()).isEqualTo(201);
        String id = (String) data(published).get("id");
        assertThat(data(call(member, HttpMethod.GET, "/announcements/" + id, null)))
                .containsEntry("body", "原内容");
        assertThat(call(token(ADMIN_B), HttpMethod.POST, "/announcements/manage/" + id + "/revoke", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(call(member, HttpMethod.POST, "/announcements/manage/" + id + "/revoke", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(call(operator, HttpMethod.POST, "/platform/announcements/" + id + "/revoke", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(call(admin, HttpMethod.POST, "/announcements/manage/" + id + "/revoke", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(admin, HttpMethod.POST, "/announcements/manage/" + id + "/revoke", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(member, HttpMethod.GET, "/announcements/" + id, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(call(member, HttpMethod.POST, "/announcements/" + id + "/read", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(list(call(admin, HttpMethod.GET, "/announcements/manage", null)))
                .anySatisfy(item -> assertThat(item).containsEntry("id", id).containsEntry("revoked", true));
        long before = unread(member);
        var expired = call(
                admin,
                HttpMethod.POST,
                "/announcements/manage",
                Map.of("title", "已到期", "body", "不再可见", "expiresAt", "2000-01-01T00:00:00Z"));
        assertThat(expired.getStatusCode().value()).isEqualTo(201);
        assertThat(unread(member)).isEqualTo(before);
        assertThat(call(
                                member,
                                HttpMethod.GET,
                                "/announcements/" + data(expired).get("id"),
                                null)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        var platform = call(
                operator,
                HttpMethod.POST,
                "/platform/announcements",
                Map.of("title", "平台撤回", "body", "所有租户", "allTenants", true));
        String platformId = (String) data(platform).get("id");
        assertThat(call(admin, HttpMethod.POST, "/announcements/manage/" + platformId + "/revoke", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(call(operator, HttpMethod.POST, "/platform/announcements/" + platformId + "/revoke", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(member, HttpMethod.GET, "/announcements/" + platformId, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        var events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher, atLeastOnce()).publish(eq(AuditEvent.TOPIC), events.capture());
        var tenantEvents = events.getAllValues().stream()
                .filter(event -> id.equals(event.resourceId()))
                .toList();
        assertThat(tenantEvents)
                .extracting(AuditEvent::action)
                .containsExactly("announcement.publish", "announcement.revoke");
        assertThat(tenantEvents).allSatisfy(event -> {
            assertThat(event.realm()).isEqualTo(AuditRealm.TENANT);
            assertThat(event.tenantId()).isEqualTo(TENANT);
            assertThat(event.actorId()).isEqualTo(IDENTITIES.get(ADMIN));
        });
        assertThat(events.getAllValues().stream()
                        .filter(event -> platformId.equals(event.resourceId()))
                        .toList())
                .allSatisfy(event -> {
                    assertThat(event.realm()).isEqualTo(AuditRealm.PLATFORM);
                    assertThat(event.tenantId()).isNull();
                })
                .extracting(AuditEvent::action)
                .containsExactly("announcement.publish", "announcement.revoke");
    }

    @Test
    void allTenantAnnouncementsReachNewTenantsAndCleanupRemovesOnlyTheDeletedTenantsData() throws Exception {
        String operator = platformToken();
        String admin = token(ADMIN);
        var global = call(
                operator,
                HttpMethod.POST,
                "/platform/announcements",
                Map.of("title", "新租户可见", "body", "全租户规则", "allTenants", true));
        String globalId = (String) data(global).get("id");
        assertThat(call(admin, HttpMethod.POST, "/announcements/" + globalId + "/read", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        UUID newTenant = UUID.randomUUID();
        deliver(newTenant, new TenantCreated(newTenant, "公告新租户", "orgadmin@example.com", "active"));
        String invitationToken = invitations.get().acceptUrl().split("invitation=")[1];
        assertThat(call(null, HttpMethod.POST, "/auth/invitations/accept", Map.of("token", invitationToken))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        var membership = list(call(admin, HttpMethod.GET, "/auth/memberships", null)).stream()
                .filter(item -> newTenant.toString().equals(item.get("tenantId")))
                .findFirst()
                .orElseThrow();
        String newToken = signed(
                KEY_FILE,
                new JWTClaimsSet.Builder()
                        .issuer("http://localhost:8080/iam")
                        .subject(IDENTITIES.get(ADMIN).toString())
                        .audience("tenant-app")
                        .claim("tenant_id", newTenant.toString())
                        .claim("membership_id", membership.get("membershipId"))
                        .claim("ver", 1));
        assertThat(list(call(newToken, HttpMethod.GET, "/announcements", null)))
                .anySatisfy(
                        item -> assertThat(item).containsEntry("id", globalId).containsEntry("read", false));
        var local =
                call(newToken, HttpMethod.POST, "/announcements/manage", Map.of("title", "新租户自己的公告", "body", "待清理"));
        assertThat(local.getStatusCode().value()).isEqualTo(201);
        assertThat(call(
                                newToken,
                                HttpMethod.POST,
                                "/announcements/" + data(local).get("id") + "/read",
                                null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        var selected = call(
                operator,
                HttpMethod.POST,
                "/platform/announcements",
                Map.of(
                        "title",
                        "多个指定租户",
                        "body",
                        "保留其他目标",
                        "allTenants",
                        false,
                        "tenantIds",
                        List.of(newTenant, TENANT_B)));
        String selectedId = (String) data(selected).get("id");
        deliver(newTenant, new TenantDeleted(newTenant));
        deliver(newTenant, new TenantDeleted(newTenant));
        assertThat(list(call(newToken, HttpMethod.GET, "/announcements/manage", null)))
                .isEmpty();
        assertThat(list(call(operator, HttpMethod.GET, "/platform/announcements", null)))
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("id", selectedId)
                        .containsEntry("tenantIds", List.of(TENANT_B.toString())));
        assertThat(list(call(token(ADMIN_B), HttpMethod.GET, "/announcements", null)))
                .extracting(item -> item.get("id"))
                .contains(globalId, selectedId);
    }

    @Test
    void inactiveMembershipsAndOperatorsWithoutPermissionCannotUseTheAnnouncementInterfaces() throws Exception {
        String limited = signed(
                PLATFORM_KEY_FILE,
                new JWTClaimsSet.Builder()
                        .issuer("http://localhost:8080/iam/platform")
                        .subject("019a0110-0000-7000-8000-0000000003d1")
                        .audience("platform-console")
                        .claim("ver", 1));
        assertThat(call(limited, HttpMethod.GET, "/platform/announcements", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(call(
                                limited,
                                HttpMethod.POST,
                                "/platform/announcements",
                                Map.of("title", "无权发布", "body", "拒绝", "allTenants", true))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(call(token(MEMBER), HttpMethod.GET, "/announcements/manage", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        String left = signed(
                KEY_FILE,
                new JWTClaimsSet.Builder()
                        .issuer("http://localhost:8080/iam")
                        .subject("019a0110-0000-7000-8000-0000000003d2")
                        .audience("tenant-app")
                        .claim("tenant_id", TENANT.toString())
                        .claim("membership_id", "019a0110-0000-7000-8000-0000000003d3")
                        .claim("ver", 1));
        assertThat(call(left, HttpMethod.GET, "/announcements", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(call(left, HttpMethod.GET, "/announcements/unread-count", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(call(left, HttpMethod.POST, "/announcements/" + UUID.randomUUID() + "/read", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
    }

    @Test
    void platformTargetRulesRejectAmbiguousEmptyAndUnknownTargets() throws Exception {
        String operator = platformToken();
        var ambiguous = call(
                operator,
                HttpMethod.POST,
                "/platform/announcements",
                Map.of("title", "无效目标", "body", "不发布", "allTenants", true, "tenantIds", List.of(TENANT)));
        assertThat(ambiguous.getStatusCode().value()).isEqualTo(400);
        assertThat(ambiguous.getBody()).containsEntry("code", "announcement.invalidTarget");
        assertThat(call(
                                operator,
                                HttpMethod.POST,
                                "/platform/announcements",
                                Map.of("title", "无效目标", "body", "不发布", "allTenants", false))
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
        assertThat(call(
                                operator,
                                HttpMethod.POST,
                                "/platform/announcements",
                                Map.of(
                                        "title",
                                        "无效目标",
                                        "body",
                                        "不发布",
                                        "allTenants",
                                        false,
                                        "tenantIds",
                                        List.of(UUID.randomUUID())))
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
        assertThat(call(token(ADMIN), HttpMethod.POST, "/announcements/manage", Map.of("title", " ", "body", "空标题"))
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
    }

    private <T> void deliver(UUID tenant, T event) {
        EventHandler<T> handler = (EventHandler<T>) handlers.stream()
                .filter(h -> h.eventType().equals(event.getClass()))
                .findFirst()
                .orElseThrow();
        CurrentTenant.run(tenant, () -> transactions.executeWithoutResult(status -> handler.handle(event)));
    }

    private static String platformToken() throws Exception {
        return signed(
                PLATFORM_KEY_FILE,
                new JWTClaimsSet.Builder()
                        .issuer("http://localhost:8080/iam/platform")
                        .subject("019a0110-0000-7000-8000-0000000000f1")
                        .audience("platform-console")
                        .claim("ver", 1));
    }

    private long unread(String token) {
        return ((Number) data(call(token, HttpMethod.GET, "/announcements/unread-count", null))
                        .get("count"))
                .longValue();
    }

    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private static List<Map<String, Object>> list(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private ResponseEntity<Map> call(String token, HttpMethod method, String uri, Object body) {
        var request =
                RestClient.create("http://127.0.0.1:" + port).method(method).uri(uri);
        if (token != null) request.headers(headers -> headers.setBearerAuth(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    private static String token(UUID membership) throws Exception {
        return signed(
                KEY_FILE,
                new JWTClaimsSet.Builder()
                        .issuer("http://localhost:8080/iam")
                        .subject(IDENTITIES.get(membership).toString())
                        .audience("tenant-app")
                        .claim("tenant_id", (membership.equals(ADMIN_B) ? TENANT_B : TENANT).toString())
                        .claim("membership_id", membership.toString())
                        .claim("ver", 1));
    }

    private static String signed(String file, JWTClaimsSet.Builder claims) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(file)));
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims.issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                        .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
