package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.iam.membership.service.MembershipStateService;
import io.saasforge.iam.tenant.service.TenantStateService;
import io.saasforge.sdk.core.MembershipStateKeys;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=${forge.test.iam-bind-address:127.0.0.1}",
            "server.servlet.context-path=/iam-test",
            // 多个用例共用 unknown、demo 等账号验证失败响应，不应互相触发 Login Lockout（LoginLockoutTest 专门覆盖）。
            "forge.iam.lockout.tenant.max-failures=1000",
            "forge.iam.lockout.tenant.ip-max-failures=1000"
        })
class TenantLoginTest {
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
        registry.add("forge.iam.signing-key-file", () -> "target/test-signing.jwk");
    }

    @LocalServerPort
    int port;

    @Value("${server.address}")
    String bindAddress;

    private String baseUrl() {
        String host = bindAddress.contains(":") ? "[" + bindAddress + "]" : bindAddress;
        return "http://" + host + ":" + port + "/iam-test";
    }

    @Test
    void emailLoginAndJwksVerifyRequiredClaims() {
        Map<?, ?> response = login("demo@example.com", "Demo-password-123!");
        var decoder = NimbusJwtDecoder.withJwkSetUri(baseUrl() + "/oauth2/jwks").build();
        var jwt = decoder.decode((String) response.get("accessToken"));
        assertThat(jwt.getSubject()).isEqualTo("019a0110-0000-7000-8000-000000000002");
        assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo("019a0110-0000-7000-8000-000000000001");
        assertThat(jwt.getClaimAsString("membership_id")).isEqualTo("019a0110-0000-7000-8000-000000000003");
        assertThat(((Number) jwt.getClaim("ver")).longValue()).isEqualTo(1);
        assertThat(jwt.getIssuer().toString()).isEqualTo("http://localhost:8080/iam");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        Map<?, ?> jwks = RestClient.create(baseUrl())
                .get()
                .uri("/oauth2/jwks")
                .retrieve()
                .body(Map.class);
        for (Object key : (List<?>) jwks.get("keys")) {
            assertThat(((Map<?, ?>) key).containsKey("d")).isFalse();
            assertThat(((Map<?, ?>) key).containsKey("p")).isFalse();
            assertThat(((Map<?, ?>) key).containsKey("q")).isFalse();
        }
    }

    @Test
    void wrongPasswordAndUnknownIdentityHaveIdenticalFailure() {
        String known = failure("demo", "incorrect");
        assertThat(failure("unknown", "incorrect"))
                .isEqualTo(known)
                .contains("\"code\":\"auth.failed\"", "\"httpStatus\":401", "\"userTip\":\"账号或密码错误，或会话已失效\"");
    }

    @Test
    @Sql("/fixtures/deleted_identities.sql")
    void deletedIdentityFailsLikeUnknownAccount() {
        // 已删除身份的密码正确且仍有 active Membership，登录结果也必须与未知账号一致。
        assertThat(failure("gone", "Demo-password-123!")).isEqualTo(failure("unknown", "Demo-password-123!"));
        assertThat(failure("gone@example.com", "Demo-password-123!"))
                .isEqualTo(failure("unknown", "Demo-password-123!"));
    }

    @Test
    @Sql({"/fixtures/multiple_memberships.sql", "/fixtures/deleted_memberships.sql"})
    void deletedMembershipsAndTenantsAreNotListedSelectedOrSwitchedInto() {
        var session = authenticate("half", "Demo-password-123!");
        assertThat((List<?>) session.body().get("memberships"))
                .<Object>extracting(item -> ((Map<?, ?>) item).get("membershipId"))
                .containsExactly(HALF_IN_DEMO_TENANT);
        for (String target : List.of(HALF_DELETED, HALF_IN_DELETED_TENANT)) {
            String code = authorize(session.cookie());
            assertThatThrownBy(() -> exchange(code, target)).isInstanceOf(HttpClientErrorException.Forbidden.class);
        }
        Map<?, ?> tokens = exchange(authorize(session.cookie()), HALF_IN_DEMO_TENANT);
        assertThat(memberships((String) tokens.get("accessToken")))
                .<Object>extracting(item -> ((Map<?, ?>) item).get("tenantId"))
                .containsExactly(DEMO_TENANT);
        for (String target : List.of(HALF_DELETED, HALF_IN_DELETED_TENANT)) {
            assertThatThrownBy(() -> switchTenant((String) tokens.get("refreshToken"), target))
                    .isInstanceOf(HttpClientErrorException.Forbidden.class);
        }
    }

    @Test
    @Sql("/fixtures/reused_identity.sql")
    void deletedAccountEmailAndMembershipCanBeCreatedAgain() {
        // 新身份能用自己的密码登录并选中重新加入的 Membership，说明三处唯一性都不再被已删除行占用。
        Map<?, ?> tokens = exchange(authorize("REBORN@example.com", "Demo-password-123!"), REBORN_MEMBERSHIP);
        assertThat(decode((String) tokens.get("accessToken")).getSubject()).isEqualTo(REBORN_IDENTITY);
        assertThat(failure("reborn", OPERATOR_PASSWORD)).isEqualTo(failure("unknown", OPERATOR_PASSWORD));
    }

    @Test
    void refreshReplayRevokesTheAuthorizationFamily() {
        Map<?, ?> original = login("demo", "Demo-password-123!");
        String refresh = (String) original.get("refreshToken");
        Map<?, ?> rotated = refresh(refresh);
        assertThat(rotated.get("accessToken")).isNotEqualTo(original.get("accessToken"));
        assertThat(rotated.get("refreshToken")).isNotEqualTo(refresh);
        assertThatThrownBy(() -> refresh(refresh)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
        assertThatThrownBy(() -> refresh("invalid")).isInstanceOf(HttpClientErrorException.Unauthorized.class);
        assertThatThrownBy(() -> refresh((String) rotated.get("refreshToken")))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
        assertThat(login("demo", "Demo-password-123!").get("accessToken")).isInstanceOf(String.class);
    }

    @Test
    @Sql("/fixtures/multiple_memberships.sql")
    void emailLookupIgnoresCaseAndMultipleMembershipsAreListedOnlyAfterPasswordCheck() {
        assertThat(login("DEMO@EXAMPLE.COM", "Demo-password-123!").get("accessToken"))
                .isInstanceOf(String.class);
        assertThat(failure("multi", "wrong")).isEqualTo(failure("unknown", "wrong"));
        assertThat(failure("lonely", "wrong")).isEqualTo(failure("unknown", "wrong"));
        List<?> memberships =
                (List<?>) authenticate("multi", "Demo-password-123!").body().get("memberships");
        // 租户已停用或 Membership 非 active 的都不可被选择，因此不列出。
        assertThat(memberships)
                .<Object>extracting(item -> ((Map<?, ?>) item).get("membershipId"))
                .containsExactlyInAnyOrder(MULTI_IN_DEMO_TENANT, MULTI_IN_SECOND_TENANT);
        assertThat(memberships)
                .<Object>extracting(item -> ((Map<?, ?>) item).get("tenantName"))
                .containsExactlyInAnyOrder("演示租户", "第二租户");
    }

    @Test
    @Sql("/fixtures/multiple_memberships.sql")
    void identityWithoutActiveMembershipSignsInButCannotObtainTokens() {
        var session = authenticate("lonely", "Demo-password-123!");
        assertThat((List<?>) session.body().get("memberships")).isEmpty();
        String code = authorize(session.cookie());
        assertThatThrownBy(() -> exchange(code, null)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    @Sql("/fixtures/multiple_memberships.sql")
    void selectedTenantAndTenantSwitchRebindTokenToTheChosenMembership() {
        var session = authenticate("multi", "Demo-password-123!");
        Map<?, ?> second = exchange(authorize(session.cookie()), MULTI_IN_SECOND_TENANT);
        assertThat(second.get("tenantName")).isEqualTo("第二租户");
        assertThat(second.get("membershipId")).isEqualTo(MULTI_IN_SECOND_TENANT);
        var claims = decode((String) second.get("accessToken"));
        assertThat(claims.getSubject()).isEqualTo(MULTI_IDENTITY);
        assertThat(claims.getClaimAsString("tenant_id")).isEqualTo(SECOND_TENANT);
        assertThat(claims.getClaimAsString("membership_id")).isEqualTo(MULTI_IN_SECOND_TENANT);

        List<?> listed = memberships((String) second.get("accessToken"));
        assertThat(listed)
                .<Object>extracting(item -> ((Map<?, ?>) item).get("tenantId"))
                .containsExactlyInAnyOrder(DEMO_TENANT, SECOND_TENANT);

        Map<?, ?> switched = switchTenant((String) second.get("refreshToken"), MULTI_IN_DEMO_TENANT);
        claims = decode((String) switched.get("accessToken"));
        assertThat(claims.getSubject()).isEqualTo(MULTI_IDENTITY);
        assertThat(claims.getClaimAsString("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat(claims.getClaimAsString("membership_id")).isEqualTo(MULTI_IN_DEMO_TENANT);
        assertThat(switched.get("tenantName")).isEqualTo("演示租户");

        // 切换沿用同一授权的刷新轮换：旧凭证失效，之后的刷新留在新租户。
        Map<?, ?> refreshed = refresh((String) switched.get("refreshToken"));
        assertThat(decode((String) refreshed.get("accessToken")).getClaimAsString("tenant_id"))
                .isEqualTo(DEMO_TENANT);
        assertThatThrownBy(() -> refresh((String) second.get("refreshToken")))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    @Sql("/fixtures/multiple_memberships.sql")
    void tenantsOutsideActiveMembershipsCannotBeSelectedOrSwitchedInto() {
        var session = authenticate("multi", "Demo-password-123!");
        for (String target : List.of(DEMO_MEMBERSHIP, MULTI_IN_SUSPENDED_TENANT, MULTI_SUSPENDED)) {
            String code = authorize(session.cookie());
            assertThatThrownBy(() -> exchange(code, target)).isInstanceOf(HttpClientErrorException.Forbidden.class);
        }
        String refreshToken = (String)
                exchange(authorize(session.cookie()), MULTI_IN_DEMO_TENANT).get("refreshToken");
        for (String target : List.of(DEMO_MEMBERSHIP, MULTI_IN_SUSPENDED_TENANT, MULTI_SUSPENDED)) {
            assertThatThrownBy(() -> switchTenant(refreshToken, target))
                    .isInstanceOf(HttpClientErrorException.Forbidden.class)
                    .satisfies(error -> assertThat(((HttpClientErrorException) error).getResponseBodyAsString())
                            .contains("\"code\":\"auth.tenantAccessDenied\"", "\"httpStatus\":403", "\"traceId\":"));
        }
        // 被拒绝的切换不消费刷新凭证，会话仍留在原租户。
        Map<?, ?> refreshed = refresh(refreshToken);
        assertThat(decode((String) refreshed.get("accessToken")).getClaimAsString("tenant_id"))
                .isEqualTo(DEMO_TENANT);
        assertThatThrownBy(() -> switchTenant("invalid", MULTI_IN_SECOND_TENANT))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    void membershipListingRequiresAValidAccessToken() {
        String anonymous = RestClient.create(baseUrl())
                .get()
                .uri("/auth/memberships")
                .exchange((req, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(401);
                    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
                    assertThat(response.getHeaders().getFirst("WWW-Authenticate"))
                            .startsWith("Bearer");
                    return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                });
        assertThat(anonymous).contains("\"code\":\"auth.unauthenticated\"", "\"httpStatus\":401", "\"traceId\":");
        String refreshToken = (String) login("demo", "Demo-password-123!").get("refreshToken");
        assertThatThrownBy(() -> memberships(refreshToken))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class)
                .satisfies(error -> assertThat(((HttpClientErrorException) error).getResponseBodyAsString())
                        .contains("\"code\":\"auth.unauthenticated\"", "\"userTip\":"));
    }

    @Test
    void replayDuringConcurrentRefreshRevokesAnyDescendantCredential() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                String old = (String) login("demo", "Demo-password-123!").get("refreshToken");
                String current = (String) refresh(old).get("refreshToken");
                var ready = new CountDownLatch(1);
                var rotating = executor.submit(() -> {
                    ready.await();
                    try {
                        return refresh(current);
                    } catch (HttpClientErrorException.Unauthorized error) {
                        return Map.of();
                    }
                });
                var replay = executor.submit(() -> {
                    ready.await();
                    assertThatThrownBy(() -> refresh(old)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
                    return true;
                });
                ready.countDown();
                var response = rotating.get();
                replay.get();
                String next = response.containsKey("refreshToken") ? (String) response.get("refreshToken") : current;
                assertThatThrownBy(() -> refresh(next)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unauthenticatedTokenRequestCannotRevokeAnAuthorization() {
        String old = (String) login("demo", "Demo-password-123!").get("refreshToken");
        String current = (String) refresh(old).get("refreshToken");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", old);
        int status = RestClient.create(baseUrl())
                .post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange((req, response) -> response.getStatusCode().value());
        assertThat(status).isIn(400, 401);
        assertThat(refresh(current).get("accessToken")).isInstanceOf(String.class);
    }

    @Autowired
    MembershipStateService membershipStates;

    @Autowired
    TenantStateService tenantStates;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    @Sql("/fixtures/revocation.sql")
    void incrementedVersionInvalidatesOldTokensAndRefreshIssuesTheCurrentVersion() {
        Map<?, ?> issued = login("rev-version", "Demo-password-123!");
        long version = ((Number) decode((String) issued.get("accessToken")).getClaim("ver")).longValue();
        assertThat(redisTemplate
                        .opsForValue()
                        .get(MembershipStateKeys.version(REV_VERSION_TENANT, REV_VERSION_MEMBERSHIP)))
                .isEqualTo(String.valueOf(version));
        assertThat(redisTemplate.opsForValue().get(MembershipStateKeys.tenantStatus(REV_VERSION_TENANT)))
                .isEqualTo("active");
        assertThat(redisTemplate
                        .opsForValue()
                        .get(MembershipStateKeys.grants(REV_VERSION_TENANT, REV_VERSION_MEMBERSHIP, version)))
                .isEqualTo("{\"roles\":[],\"permissions\":[],\"subject\":{\"departmentPaths\":[],"
                        + "\"primaryDepartmentPath\":null,\"positionIds\":[],\"attributes\":{}}}");

        membershipStates.incrementVersion(REV_VERSION_MEMBERSHIP);
        // 网关据此拒绝仍携带旧 ver 的 Access Token。
        assertThat(redisTemplate
                        .opsForValue()
                        .get(MembershipStateKeys.version(REV_VERSION_TENANT, REV_VERSION_MEMBERSHIP)))
                .isEqualTo(String.valueOf(version + 1));

        Map<?, ?> refreshed = refresh((String) issued.get("refreshToken"));
        assertThat(((Number) decode((String) refreshed.get("accessToken")).getClaim("ver")).longValue())
                .isEqualTo(version + 1);
        assertThat(redisTemplate
                        .opsForValue()
                        .get(MembershipStateKeys.grants(REV_VERSION_TENANT, REV_VERSION_MEMBERSHIP, version + 1)))
                .isNotNull();
    }

    @Test
    @Sql("/fixtures/revocation.sql")
    void deactivatedMembershipInvalidatesTokensAndCannotRefresh() {
        Map<?, ?> issued = login("rev-deactivate", "Demo-password-123!");
        long version = ((Number) decode((String) issued.get("accessToken")).getClaim("ver")).longValue();

        membershipStates.deactivate(REV_DEACTIVATE_MEMBERSHIP);

        assertThat(redisTemplate
                        .opsForValue()
                        .get(MembershipStateKeys.version(REV_DEACTIVATE_TENANT, REV_DEACTIVATE_MEMBERSHIP)))
                .isEqualTo(String.valueOf(version + 1));
        assertThatThrownBy(() -> refresh((String) issued.get("refreshToken")))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    @Sql("/fixtures/revocation.sql")
    void suspendedTenantIsPublishedAndCannotRefresh() {
        Map<?, ?> issued = login("rev-suspend", "Demo-password-123!");

        tenantStates.apply(REV_SUSPEND_TENANT, "rev-suspend", "suspended", 1);

        assertThat(redisTemplate.opsForValue().get(MembershipStateKeys.tenantStatus(REV_SUSPEND_TENANT)))
                .isEqualTo("suspended");
        assertThatThrownBy(() -> refresh((String) issued.get("refreshToken")))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    void refreshRepublishesStateMissingFromRedis() {
        Map<?, ?> issued = login("demo", "Demo-password-123!");
        var tenant = UUID.fromString(DEMO_TENANT);
        var membership = UUID.fromString(DEMO_MEMBERSHIP);
        // Redis 丢失状态时网关拒绝请求；刷新必须恢复状态，否则用户只能重新登录。
        redisTemplate.delete(List.of(
                MembershipStateKeys.version(tenant, membership),
                MembershipStateKeys.tenantStatus(tenant),
                MembershipStateKeys.tenantIpAllowlist(tenant)));

        Map<?, ?> refreshed = refresh((String) issued.get("refreshToken"));

        long version = ((Number) decode((String) refreshed.get("accessToken")).getClaim("ver")).longValue();
        assertThat(redisTemplate.opsForValue().get(MembershipStateKeys.version(tenant, membership)))
                .isEqualTo(String.valueOf(version));
        assertThat(redisTemplate.opsForValue().get(MembershipStateKeys.tenantStatus(tenant)))
                .isEqualTo("active");
        assertThat(redisTemplate.opsForValue().get(MembershipStateKeys.tenantIpAllowlist(tenant)))
                .isEqualTo("*");
    }

    private static final UUID REV_VERSION_TENANT = UUID.fromString("019a0110-0000-7000-8000-00000000000e");
    private static final UUID REV_VERSION_MEMBERSHIP = UUID.fromString("019a0110-0000-7000-8000-000000000013");
    private static final UUID REV_DEACTIVATE_TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000010");
    private static final UUID REV_DEACTIVATE_MEMBERSHIP = UUID.fromString("019a0110-0000-7000-8000-000000000014");
    private static final UUID REV_SUSPEND_TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000012");

    private static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    private static final String DEMO_TENANT = "019a0110-0000-7000-8000-000000000001";
    private static final String SECOND_TENANT = "019a0110-0000-7000-8000-000000000004";
    private static final String DEMO_MEMBERSHIP = "019a0110-0000-7000-8000-000000000003";
    private static final String MULTI_IDENTITY = "019a0110-0000-7000-8000-000000000005";
    private static final String MULTI_IN_DEMO_TENANT = "019a0110-0000-7000-8000-000000000006";
    private static final String MULTI_IN_SECOND_TENANT = "019a0110-0000-7000-8000-000000000007";
    private static final String MULTI_IN_SUSPENDED_TENANT = "019a0110-0000-7000-8000-00000000000a";
    private static final String MULTI_SUSPENDED = "019a0110-0000-7000-8000-00000000000b";
    private static final String REBORN_IDENTITY = "019a0110-0000-7000-8000-000000000028";
    private static final String REBORN_MEMBERSHIP = "019a0110-0000-7000-8000-00000000002a";
    private static final String OPERATOR_PASSWORD = "Operator-password-123!";
    private static final String HALF_IN_DEMO_TENANT = "019a0110-0000-7000-8000-000000000024";
    private static final String HALF_DELETED = "019a0110-0000-7000-8000-000000000025";
    private static final String HALF_IN_DELETED_TENANT = "019a0110-0000-7000-8000-000000000026";

    private record SignedIn(String cookie, Map<?, ?> body) {}

    private Map<?, ?> login(String account, String password) {
        return exchange(authorize(account, password), null);
    }

    private Map<?, ?> exchange(String code, String membershipId) {
        var body = new HashMap<String, String>(Map.of("code", code, "codeVerifier", VERIFIER));
        if (membershipId != null) {
            body.put("membershipId", membershipId);
        }
        return data(RestClient.create(baseUrl())
                .post()
                .uri("/auth/exchange")
                .body(body)
                .retrieve()
                .body(Map.class));
    }

    private Map<?, ?> switchTenant(String refreshToken, String membershipId) {
        return data(RestClient.create(baseUrl())
                .post()
                .uri("/auth/switch")
                .body(Map.of("refreshToken", refreshToken, "membershipId", membershipId))
                .retrieve()
                .body(Map.class));
    }

    private List<?> memberships(String accessToken) {
        var response = RestClient.create(baseUrl())
                .get()
                .uri("/auth/memberships")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .toEntity(Map.class);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        return (List<?>) data(response.getBody());
    }

    private Jwt decode(String accessToken) {
        return NimbusJwtDecoder.withJwkSetUri(baseUrl() + "/oauth2/jwks")
                .build()
                .decode(accessToken);
    }

    private RestClient noRedirectClient() {
        return RestClient.builder()
                .baseUrl(baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
    }

    private SignedIn authenticate(String account, String password) {
        var login = noRedirectClient()
                .post()
                .uri("/auth/login")
                .body(Map.of("account", account, "password", password))
                .retrieve()
                .toEntity(Map.class);
        assertThat(login.getHeaders().getCacheControl()).isEqualTo("no-store");
        return new SignedIn(login.getHeaders().getFirst("Set-Cookie").split(";")[0], data(login.getBody()));
    }

    private String authorize(String account, String password) {
        return authorize(authenticate(account, password).cookie());
    }

    private String authorize(String cookie) {
        try {
            String challenge = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            var response = noRedirectClient()
                    .get()
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
            assertThat(response.getStatusCode().value()).isEqualTo(302);
            var query = UriComponentsBuilder.fromUri(response.getHeaders().getLocation())
                    .build()
                    .getQueryParams();
            assertThat(query.getFirst("state")).isEqualTo("test-state");
            assertThat(query.getFirst("error")).isNull();
            return query.getFirst("code");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void logoutInvalidatesIamSessionAndRequiresFreshCredentials() {
        var client = RestClient.builder()
                .baseUrl(baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
        var login = client.post()
                .uri("/auth/login")
                .body(Map.of("account", "demo", "password", "Demo-password-123!"))
                .retrieve()
                .toEntity(Map.class);
        String cookie = login.getHeaders().getFirst("Set-Cookie").split(";")[0];
        var logout = client.post()
                .uri("/auth/logout")
                .header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .toEntity(Map.class);
        assertThat(logout.getStatusCode().value()).isEqualTo(200);
        assertThat(logout.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(logout.getBody()).containsEntry("code", "ok").containsEntry("httpStatus", 200);
        assertThat(logout.getBody()).doesNotContainKey("data");
        var response = client.get()
                .uri(builder -> builder.path("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "tenant-app")
                        .queryParam("redirect_uri", "http://localhost:5174/")
                        .queryParam("scope", "tenant")
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
                        .build())
                .header("Cookie", cookie)
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(response).isIn(401, 403);
        assertThat(login("demo", "Demo-password-123!").get("accessToken")).isInstanceOf(String.class);
    }

    @Test
    void standardTokenEndpointEnforcesPkceAndRotatesRefreshToken() {
        String code = authorize("demo", "Demo-password-123!");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", "http://localhost:5174/");
        form.add("code_verifier", VERIFIER);
        Map<?, ?> response = tokenGrant(form);
        assertThat(response.get("access_token")).isInstanceOf(String.class);
        // 标准 Token 端点保持 RFC 6749 的错误格式，不使用信封
        assertThatThrownBy(() -> tokenGrant(form))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .satisfies(error -> assertThat(((HttpClientErrorException) error).getResponseBodyAs(Map.class))
                        .containsKey("error")
                        .doesNotContainKey("traceId"));
        String otherCode = authorize("demo", "Demo-password-123!");
        form.set("code", otherCode);
        form.set("code_verifier", "wrong-verifier-abcdefghijklmnopqrstuvwxyz0123456789");
        assertThatThrownBy(() -> tokenGrant(form)).isInstanceOf(HttpClientErrorException.BadRequest.class);
        Map<?, ?> fresh = login("demo", "Demo-password-123!");
        var refresh = new LinkedMultiValueMap<String, String>();
        refresh.add("grant_type", "refresh_token");
        refresh.add("refresh_token", (String) fresh.get("refreshToken"));
        assertThat(tokenGrant(refresh).get("access_token")).isInstanceOf(String.class);
        assertThatThrownBy(() -> tokenGrant(refresh)).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    void concurrentRefreshOnlyConsumesCredentialOnce() throws Exception {
        String token = (String) login("demo", "Demo-password-123!").get("refreshToken");
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(1);
        Callable<Integer> request = () -> {
            ready.await();
            return RestClient.create(baseUrl())
                    .post()
                    .uri("/auth/refresh")
                    .body(Map.of("refreshToken", token))
                    .exchange((req, response) -> response.getStatusCode().value());
        };
        try {
            var first = executor.submit(request);
            var second = executor.submit(request);
            ready.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 401);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void consumedAuthorizationCodeAndAccessTokenCannotBeUsedAsRefreshCredentials() {
        String code = authorize("demo", "Demo-password-123!");
        Map<?, ?> issued = data(RestClient.create(baseUrl())
                .post()
                .uri("/auth/exchange")
                .body(Map.of("code", code, "codeVerifier", VERIFIER))
                .retrieve()
                .body(Map.class));
        assertThatThrownBy(() -> refresh(code)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", (String) issued.get("accessToken"));
        assertThatThrownBy(() -> tokenGrant(form)).isInstanceOf(HttpClientErrorException.BadRequest.class);
        assertThat(refresh((String) issued.get("refreshToken")).get("accessToken"))
                .isInstanceOf(String.class);
    }

    @Test
    void irrelevantRefreshParameterCannotBypassAuthorizationCodeConsumption() throws Exception {
        String code = authorize("demo", "Demo-password-123!");
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(1);
        Function<String, Callable<Integer>> request = noise -> () -> {
            ready.await();
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "authorization_code");
            form.add("code", code);
            form.add("redirect_uri", "http://localhost:5174/");
            form.add("code_verifier", VERIFIER);
            form.add("refresh_token", noise);
            return RestClient.create(baseUrl())
                    .post()
                    .uri("/oauth2/token")
                    .headers(headers -> headers.setBasicAuth("tenant-app", "test-client-secret"))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((req, response) -> response.getStatusCode().value());
        };
        try {
            var first = executor.submit(request.apply("unrelated-one"));
            var second = executor.submit(request.apply("unrelated-two"));
            ready.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 400);
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<?, ?> tokenGrant(LinkedMultiValueMap<String, String> form) {
        return RestClient.create(baseUrl())
                .post()
                .uri("/oauth2/token")
                .headers(headers -> headers.setBasicAuth("tenant-app", "test-client-secret"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
    }

    private Map<?, ?> refresh(String token) {
        return data(RestClient.create(baseUrl())
                .post()
                .uri("/auth/refresh")
                .body(Map.of("refreshToken", token))
                .retrieve()
                .body(Map.class));
    }

    /** 失败信封去掉每次请求都不同的 traceId 后的原文。 */
    private String failure(String account, String password) {
        String body = noRedirectClient()
                .post()
                .uri("/auth/login")
                .body(Map.of("account", account, "password", password))
                .exchange((req, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(401);
                    // 错误信封同样不能被缓存
                    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
                    return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                });
        assertThat(body).contains("\"traceId\":");
        return body.replaceAll("\"traceId\":\"[0-9a-f]+\"", "");
    }

    private static <T> T data(Map<?, ?> envelope) {
        assertThat(envelope.get("code")).isEqualTo("ok");
        @SuppressWarnings("unchecked")
        T data = (T) envelope.get("data");
        return data;
    }

    @Test
    void accountAndPasswordIssueTokens() {
        Map<?, ?> response = login("demo", "Demo-password-123!");
        assertThat(response.get("accessToken")).isInstanceOf(String.class);
        assertThat(response.get("refreshToken")).isInstanceOf(String.class);
        assertThat(((Number) response.get("expiresIn")).intValue()).isBetween(899, 900);
    }
}
