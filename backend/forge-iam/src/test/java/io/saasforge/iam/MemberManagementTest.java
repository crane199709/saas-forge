package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.iam.verification.service.ContactMessage;
import io.saasforge.iam.verification.service.EmailSender;
import io.saasforge.iam.verification.service.InvitationMessage;
import io.saasforge.iam.verification.service.SmsSender;
import io.saasforge.sdk.core.MembershipStateKeys;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** 邀请、停用、恢复、移除成员，成员主动退出与 Owner 转让（工单 17）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@Sql("/fixtures/members.sql")
class MemberManagementTest {
    static final String KEY_FILE = "target/test-members-signing.jwk";
    static final String PASSWORD = "Demo-password-123!";
    static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-0000000000d0");
    static final UUID OWNER = UUID.fromString("019a0110-0000-7000-8000-0000000000d2");
    static final UUID ADMIN = UUID.fromString("019a0110-0000-7000-8000-0000000000d4");
    static final UUID SUSPENDED = UUID.fromString("019a0110-0000-7000-8000-0000000000d6");
    static final UUID LEAVING = UUID.fromString("019a0110-0000-7000-8000-0000000000d8");
    static final UUID TARGET = UUID.fromString("019a0110-0000-7000-8000-0000000000da");
    static final Map<UUID, UUID> IDENTITIES = Map.of(
            OWNER, UUID.fromString("019a0110-0000-7000-8000-0000000000d1"),
            ADMIN, UUID.fromString("019a0110-0000-7000-8000-0000000000d3"),
            SUSPENDED, UUID.fromString("019a0110-0000-7000-8000-0000000000d5"),
            LEAVING, UUID.fromString("019a0110-0000-7000-8000-0000000000d7"),
            TARGET, UUID.fromString("019a0110-0000-7000-8000-0000000000d9"));

    static final SharedContainers.Postgres postgres = SharedContainers.postgres();

    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.iam.client-secret", () -> "test-client-secret");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> KEY_FILE);
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

        void deliver(String target, ContactMessage message) {
            if (message instanceof InvitationMessage invitation) latest.put(target, invitation);
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
    RoleService roles;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void suspendedMemberIsRejectedOnTheNextRequestAndCanBeResumed() throws Exception {
        Map<?, ?> issued = signIn("membersuspend");
        long issuedVersion = ((Number) NimbusJwtDecoder.withJwkSetUri(baseUrl() + "/oauth2/jwks")
                        .build()
                        .decode((String) issued.get("accessToken"))
                        .getClaim("ver"))
                .longValue();
        assertThat(version(SUSPENDED)).isEqualTo(issuedVersion);

        assertThat(call(ADMIN, HttpMethod.POST, "/members/" + SUSPENDED + "/suspend", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        // 网关逐个请求比对 Token 的 ver 与 Redis 中的版本（见 GatewaySecurityTest）：版本已前进，下一次请求即被拒绝，刷新也失败。
        assertThat(version(SUSPENDED)).isGreaterThan(issuedVersion);
        assertThatThrownBy(() -> refresh((String) issued.get("refreshToken")))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
        assertThat(member(ADMIN, SUSPENDED)).containsEntry("status", "suspended");
        assertThat(code(call(ADMIN, HttpMethod.POST, "/members/" + SUSPENDED + "/suspend", null)))
                .isEqualTo("member.illegalStatus");

        assertThat(call(ADMIN, HttpMethod.POST, "/members/" + SUSPENDED + "/resume", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(member(ADMIN, SUSPENDED)).containsEntry("status", "active");
        // 恢复后重新登录可以进入，签发的版本即当前版本。
        Map<?, ?> again = signIn("membersuspend");
        assertThat(((Number) NimbusJwtDecoder.withJwkSetUri(baseUrl() + "/oauth2/jwks")
                                .build()
                                .decode((String) again.get("accessToken"))
                                .getClaim("ver"))
                        .longValue())
                .isEqualTo(version(SUSPENDED));
    }

    @Test
    void ownerCannotBeSuspendedOrRemovedAndTransferKeepsExactlyOneOwner() throws Exception {
        assertThat(code(call(ADMIN, HttpMethod.POST, "/members/" + OWNER + "/suspend", null)))
                .isEqualTo("member.ownerProtected");
        assertThat(code(call(ADMIN, HttpMethod.DELETE, "/members/" + OWNER, null)))
                .isEqualTo("member.ownerProtected");
        assertThat(code(call(OWNER, HttpMethod.POST, "/members/me/leave", null)))
                .isEqualTo("member.ownerProtected");
        assertThat(member(ADMIN, OWNER)).containsEntry("status", "active").containsEntry("owner", true);

        // 只有 Owner 能转让，且只能转给另一个正常状态的成员。
        assertThat(code(transfer(ADMIN, TARGET))).isEqualTo("member.notOwner");
        assertThat(code(transfer(OWNER, OWNER))).isEqualTo("member.invalidTransferTarget");
        assertThat(code(transfer(OWNER, UUID.randomUUID()))).isEqualTo("member.invalidTransferTarget");

        long ownerVersion = version(OWNER);
        long targetVersion = version(TARGET);
        assertThat(transfer(OWNER, TARGET).getStatusCode().value()).isEqualTo(200);
        assertThat(roles.ownerOf(TENANT)).contains(TARGET);
        // 原 Owner 降为 Tenant Admin；双方旧 Token 立即失效。
        assertThat(roles.grantsOf(OWNER).roles()).containsExactly(RoleService.TENANT_ADMIN);
        assertThat(roles.grantsOf(TARGET).roles()).contains(RoleService.OWNER);
        assertThat(version(OWNER)).isGreaterThan(ownerVersion);
        assertThat(version(TARGET)).isGreaterThan(targetVersion);
        assertThat(members(ADMIN, null, null).stream().filter(row -> Boolean.TRUE.equals(row.get("owner"))))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("membershipId", TARGET.toString()));
        // 原 Owner 不再受保护，新 Owner 受保护；原 Owner 已不能再转让。
        assertThat(code(call(ADMIN, HttpMethod.POST, "/members/" + TARGET + "/suspend", null)))
                .isEqualTo("member.ownerProtected");
        assertThat(code(transfer(OWNER, TARGET))).isEqualTo("member.notOwner");

        // 转回，恢复其他用例依赖的初始 Owner。
        assertThat(transfer(TARGET, OWNER).getStatusCode().value()).isEqualTo(200);
        assertThat(roles.ownerOf(TENANT)).contains(OWNER);
        assertThat(roles.grantsOf(TARGET).roles()).containsExactly(RoleService.TENANT_ADMIN);
        assignRoles(ADMIN, TARGET, List.of());
    }

    @Test
    void invitationPresetsRolesAndDepartmentAndRemovedMemberCanBeInvitedAgain() throws Exception {
        String role = (String) data(call(
                        ADMIN,
                        HttpMethod.POST,
                        "/roles",
                        Map.of("code", "invitee-role", "name", "受邀角色", "permissions", List.of("iam:org:manage"))))
                .get("id");
        String department = (String) data(call(ADMIN, HttpMethod.POST, "/org/departments", Map.of("name", "受邀部门")))
                .get("id");

        var invited = call(
                ADMIN,
                HttpMethod.POST,
                "/members/invitations",
                Map.of("contact", "Newcomer@Example.com", "roleIds", List.of(role), "primaryDepartmentId", department));
        assertThat(invited.getStatusCode().value()).isEqualTo(201);
        assertThat(data(invited)).containsEntry("status", "invited").containsEntry("resent", false);
        UUID newcomer = UUID.fromString((String) data(invited).get("membershipId"));
        assertThat(inbox.latest.get("Newcomer@Example.com").requiresCredentials())
                .isTrue();
        assertThat(roles.grantsOf(newcomer).roles()).containsExactly("invitee-role");
        assertThat(orgAssignment(newcomer)).containsEntry("primaryDepartmentId", department);
        assertThat(members(ADMIN, "newcomer", "invited"))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("membershipId", newcomer.toString())
                        .containsEntry("email", "Newcomer@Example.com")
                        .containsEntry("account", null));

        // 已在租户中的身份不能再被邀请；邀请中的可以重发。
        assertThat(code(call(
                        ADMIN, HttpMethod.POST, "/members/invitations", Map.of("contact", "memberadmin@example.com"))))
                .isEqualTo("member.alreadyMember");
        var resent = call(ADMIN, HttpMethod.POST, "/members/invitations", Map.of("contact", "newcomer@example.com"));
        assertThat(resent.getStatusCode().value()).isEqualTo(201);
        assertThat(data(resent))
                .containsEntry("membershipId", newcomer.toString())
                .containsEntry("resent", true);

        // 移除（撤回邀请）后成员为已离开：角色与组织分配清除，旧链接失效，列表仍可按状态查到。
        String revokedToken = inbox.token("newcomer@example.com");
        assertThat(call(ADMIN, HttpMethod.DELETE, "/members/" + newcomer, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(roles.grantsOf(newcomer).roles()).isEmpty();
        assertThat(members(ADMIN, null, "left"))
                .anySatisfy(row -> assertThat(row).containsEntry("membershipId", newcomer.toString()));
        assertThat(members(ADMIN, null, "invited"))
                .noneSatisfy(row -> assertThat(row).containsEntry("membershipId", newcomer.toString()));
        assertThat(code(post("/auth/invitations/accept", Map.of("token", revokedToken))))
                .isEqualTo("invitation.invalid");
        assertThat(code(call(ADMIN, HttpMethod.DELETE, "/members/" + newcomer, null)))
                .isEqualTo("member.illegalStatus");

        // 再次邀请回到同一个 Membership，接受后成为正常成员。
        var again = call(ADMIN, HttpMethod.POST, "/members/invitations", Map.of("contact", "newcomer@example.com"));
        assertThat(data(again))
                .containsEntry("membershipId", newcomer.toString())
                .containsEntry("resent", false);
        var accepted = post(
                "/auth/invitations/accept",
                Map.of("token", inbox.token("newcomer@example.com"), "account", "newcomer", "password", PASSWORD));
        assertThat(accepted.getStatusCode().value()).isEqualTo(200);
        assertThat(member(ADMIN, newcomer)).containsEntry("status", "active").containsEntry("account", "newcomer");
    }

    @Test
    void memberLeavesOnTheirOwnAndAdminsCannotChangeIdentityCredentials() throws Exception {
        // 没有 iam:member:manage 的成员不能管理成员。
        assertThat(call(LEAVING, HttpMethod.GET, "/members", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(code(call(LEAVING, HttpMethod.POST, "/members/" + TARGET + "/suspend", null)))
                .isEqualTo("auth.forbidden");

        // 成员接口不提供修改 Identity 密码、邮箱、手机号的能力。
        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH)) {
            var attempt = call(
                    ADMIN,
                    method,
                    "/members/" + LEAVING,
                    Map.of("email", "hijacked@example.com", "phone", "13900000000", "password", "Hijacked-123!"));
            assertThat(attempt.getStatusCode().is4xxClientError()).isTrue();
        }
        assertThat(member(ADMIN, LEAVING))
                .containsEntry("email", "memberleave@example.com")
                .containsEntry("phone", null);
        assertThat(post("/auth/login", Map.of("account", "memberleave", "password", PASSWORD))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        String role = (String) data(call(
                        ADMIN,
                        HttpMethod.POST,
                        "/roles",
                        Map.of("code", "leaver-role", "name", "退出者角色", "permissions", List.of())))
                .get("id");
        assignRoles(ADMIN, LEAVING, List.of(role));
        long before = version(LEAVING);

        assertThat(call(LEAVING, HttpMethod.POST, "/members/me/leave", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(version(LEAVING)).isGreaterThan(before);
        assertThat(roles.grantsOf(LEAVING).roles()).isEmpty();
        assertThat(member(ADMIN, LEAVING)).containsEntry("status", "left");
        // 已离开的成员不能再被分配角色。
        assertThat(code(assignRoles(ADMIN, LEAVING, List.of(role)))).isEqualTo("role.membershipNotFound");
    }

    private ResponseEntity<Map> transfer(UUID owner, UUID target) throws Exception {
        return call(owner, HttpMethod.PUT, "/members/owner", Map.of("membershipId", target.toString()));
    }

    private ResponseEntity<Map> assignRoles(UUID admin, UUID membership, List<String> roleIds) throws Exception {
        return call(admin, HttpMethod.PUT, "/roles/memberships/" + membership, Map.of("roleIds", roleIds));
    }

    private Map<String, Object> orgAssignment(UUID membership) throws Exception {
        for (Object row : (List<?>) call(ADMIN, HttpMethod.GET, "/org/memberships?page=1&size=100", null)
                .getBody()
                .get("data")) {
            if (membership.toString().equals(((Map<?, ?>) row).get("membershipId"))) return object(row);
        }
        throw new AssertionError("Membership not listed: " + membership);
    }

    private List<Map<String, Object>> members(UUID admin, String keyword, String status) throws Exception {
        var uri =
                UriComponentsBuilder.fromPath("/members").queryParam("page", 1).queryParam("size", 100);
        if (keyword != null) uri.queryParam("keyword", keyword);
        if (status != null) uri.queryParam("status", status);
        var response = call(admin, HttpMethod.GET, uri.toUriString(), null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return ((List<?>) response.getBody().get("data"))
                .stream().map(MemberManagementTest::object).toList();
    }

    private Map<String, Object> member(UUID admin, UUID membership) throws Exception {
        return members(admin, null, null).stream()
                .filter(row -> membership.toString().equals(row.get("membershipId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Membership not listed: " + membership));
    }

    private long version(UUID membership) {
        String value = redisTemplate.opsForValue().get(MembershipStateKeys.version(TENANT, membership));
        return value == null ? 1 : Long.parseLong(value);
    }

    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return object(response.getBody().get("data"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static Object code(ResponseEntity<Map> response) {
        return response.getBody().get("code");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private ResponseEntity<Map> post(String uri, Map<String, ?> body) {
        return RestClient.create(baseUrl())
                .post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new HashMap<>(body))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    private ResponseEntity<Map> call(UUID membership, HttpMethod method, String uri, Object body) throws Exception {
        String token = token(membership);
        var request =
                RestClient.create(baseUrl()).method(method).uri(uri).headers(headers -> headers.setBearerAuth(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    // 以 iam 自己的签名密钥签发 Access Token，省去完整的登录流程；版本比对由网关负责，见 GatewaySecurityTest。
    private static String token(UUID membership) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(KEY_FILE)));
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8080/iam")
                .subject(IDENTITIES.get(membership).toString())
                .audience("tenant-app")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", TENANT.toString())
                .claim("membership_id", membership.toString())
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    // 停用与恢复要验证真实签发的 ver 与刷新，走完整的登录、授权码与换发流程；身份只有一个有效 Membership，自动进入。
    private Map<?, ?> signIn(String account) throws Exception {
        RestClient client = RestClient.builder()
                .baseUrl(baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
        var login = client.post()
                .uri("/auth/login")
                .body(Map.of("account", account, "password", PASSWORD))
                .retrieve()
                .toEntity(Map.class);
        String cookie = login.getHeaders().getFirst("Set-Cookie").split(";")[0];
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        var authorized = client.get()
                .uri(builder -> builder.path("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "tenant-app")
                        .queryParam("redirect_uri", "http://localhost:5174/")
                        .queryParam("scope", "tenant")
                        .queryParam("state", "test-state")
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("code_challenge", challenge)
                        .build())
                .header("Cookie", cookie)
                .retrieve()
                .toBodilessEntity();
        String code = UriComponentsBuilder.fromUri(authorized.getHeaders().getLocation())
                .build()
                .getQueryParams()
                .getFirst("code");
        return (Map<?, ?>) RestClient.create(baseUrl())
                .post()
                .uri("/auth/exchange")
                .body(Map.of("code", code, "codeVerifier", VERIFIER))
                .retrieve()
                .body(Map.class)
                .get("data");
    }

    private Map<?, ?> refresh(String refreshToken) {
        return RestClient.create(baseUrl())
                .post()
                .uri("/auth/refresh")
                .body(Map.of("refreshToken", refreshToken))
                .retrieve()
                .body(Map.class);
    }
}
