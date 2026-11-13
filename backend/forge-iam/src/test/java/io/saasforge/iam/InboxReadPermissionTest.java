package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** 通过 HTTP 与公开成员服务验证角色、套餐和新成员默认授权。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/plans.sql")
class InboxReadPermissionTest {
    static final String KEY_FILE = "target/test-inbox-read-signing.jwk";
    static final UUID TENANT = UUID.fromString("019a0120-0000-7000-8000-0000000000a0");
    static final UUID ADMIN = UUID.fromString("019a0120-0000-7000-8000-0000000000a2");
    static final UUID MEMBER = UUID.fromString("019a0120-0000-7000-8000-0000000000a4");
    static final UUID IDENTITY = UUID.fromString("019a0120-0000-7000-8000-0000000000a3");
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
    }

    @LocalServerPort
    int port;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    io.saasforge.iam.membership.service.MembershipService memberships;

    @Autowired
    io.saasforge.iam.role.service.RoleService roles;

    @Test
    void newMembershipGetsAnEditableReadRoleAndRepeatedInvitationDoesNotRestoreRevokedAccess() throws Exception {
        var member = transaction.execute(
                status -> memberships.invite(TENANT, UUID.fromString("019a0110-0000-7000-8000-000000000002")));
        assertThat(roles.grantsOf(member.membershipId()).permissions())
                .contains("todo:read", "notification:read", "announcement:read");
        mutate(HttpMethod.PUT, "/roles/memberships/" + member.membershipId(), Map.of("roleIds", List.of()));
        transaction.executeWithoutResult(
                status -> memberships.invite(TENANT, UUID.fromString("019a0110-0000-7000-8000-000000000002")));
        assertThat(roles.grantsOf(member.membershipId()).permissions()).isEmpty();
    }

    @Test
    void readingRequiresAnExplicitRoleGrantAndRemainsInsideThePlan() throws Exception {
        mutate(HttpMethod.PUT, "/roles/memberships/" + MEMBER, Map.of("roleIds", List.of()));
        assertReadEndpoints(MEMBER, 403);
        var created = mutate(
                HttpMethod.POST,
                "/roles",
                Map.of(
                        "code",
                        "inbox-test",
                        "name",
                        "消息读取",
                        "permissions",
                        List.of("todo:read", "notification:read", "announcement:read")));
        String roleId = (String) ((Map<?, ?>) created.getBody().get("data")).get("id");
        mutate(HttpMethod.PUT, "/roles/memberships/" + MEMBER, Map.of("roleIds", List.of(roleId)));
        assertReadEndpoints(MEMBER, 200);
        UUID plan = UUID.randomUUID();
        deliver(TENANT, new TenantPlanChanged(TENANT, plan, "inbox", List.of(), List.of(), null, null, 100));
        assertReadEndpoints(MEMBER, 403);
        assertReadEndpoints(ADMIN, 403);
        deliver(
                TENANT,
                new TenantPlanChanged(
                        TENANT,
                        plan,
                        "inbox",
                        List.of("todo:read", "notification:read", "announcement:read"),
                        List.of(),
                        null,
                        null,
                        101));
        assertReadEndpoints(MEMBER, 200);
        mutate(HttpMethod.PUT, "/roles/" + roleId + "/permissions", Map.of("permissions", List.of()));
        assertReadEndpoints(MEMBER, 403);
    }

    private void assertReadEndpoints(UUID member, int expected) throws Exception {
        for (String path : List.of(
                "/todos",
                "/notifications",
                "/notifications/unread-count",
                "/announcements",
                "/announcements/unread-count")) {
            assertThat(readCall(member, HttpMethod.GET, path)).as(path).isEqualTo(expected);
        }
        for (String path : List.of(
                "/notifications/read-all",
                "/notifications/" + UUID.randomUUID() + "/read",
                "/announcements/" + UUID.randomUUID() + "/read")) {
            assertThat(readCall(member, HttpMethod.POST, path))
                    .as(path)
                    .isEqualTo(expected == 200 && !path.endsWith("read-all") ? 404 : expected);
        }
        assertThat(readCall(member, HttpMethod.GET, "/announcements/" + UUID.randomUUID()))
                .isEqualTo(expected == 200 ? 404 : expected);
    }

    private int readCall(UUID member, HttpMethod method, String path) throws Exception {
        String accessToken = token(TENANT, member);
        return RestClient.create("http://127.0.0.1:" + port)
                .method(method)
                .uri(path)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toBodilessEntity()
                .getStatusCode()
                .value();
    }

    private ResponseEntity<Map> mutate(HttpMethod method, String path, Object body) throws Exception {
        String accessToken = token(TENANT, ADMIN);
        var response = RestClient.create("http://127.0.0.1:" + port)
                .method(method)
                .uri(path)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as(path + " " + response.getBody())
                .isTrue();
        return response;
    }

    @SuppressWarnings("unchecked")
    private <T> void deliver(UUID tenant, T event) {
        var handler = (EventHandler<T>) handlers.stream()
                .filter(candidate -> candidate.eventType() == event.getClass())
                .findFirst()
                .orElseThrow();
        CurrentTenant.run(tenant, () -> transaction.executeWithoutResult(status -> handler.handle(event)));
    }

    private static String token(UUID tenant, UUID membership) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(KEY_FILE)));
        UUID identity = membership.equals(ADMIN) ? UUID.fromString("019a0120-0000-7000-8000-0000000000a1") : IDENTITY;
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8080/iam")
                .subject(identity.toString())
                .audience("tenant-app")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", tenant.toString())
                .claim("membership_id", membership.toString())
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
