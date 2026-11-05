package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.OperatorStateKeys;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.DataAttribute;
import io.saasforge.sdk.core.ResourceManifest.DataResource;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 平台侧权限（工单 22）：超级管理员、平台角色与 Operator 管理，平台与租户的权限数据分离，停用与授权变更即时生效。
 * 网关按 Redis 中的版本拒绝旧 Token 由 GatewaySecurityTest 验证，这里验证 iam 发布的版本与明细。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("unchecked")
class PlatformRoleTest {
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

    static final UUID SUPER_ADMIN_ID = UUID.fromString("019a0110-0000-7000-8000-0000000000f1");
    static final String SUPER_ADMIN_PASSWORD = "Operator-password-123!";
    static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    static final String REGION_CONDITION =
            "{\"attr\":\"resource.region\",\"op\":\"eq\",\"value\":{\"ref\":\"subject.attributes.region\"}}";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    PermissionService permissions;

    @Autowired
    StringRedisTemplate redisTemplate;

    String admin;

    // forge-platform 以 Platform Realm 清单上报的权限码与数据资源；重复上报相同内容为无操作。
    @BeforeEach
    void reportPlatformManifest() {
        permissions.report(
                "forge-platform",
                new ResourceManifest(
                        "forge-platform",
                        "1",
                        List.of(
                                new Permission("platform:tenant:read", "查看租户", "租户"),
                                new Permission("platform:tenant:manage", "管理租户", "租户")),
                        List.of(),
                        List.of(),
                        List.of(new DataResource(
                                "tenant",
                                "租户",
                                "tenant",
                                List.of(
                                        new DataAttribute("ownerId", "创建人", "uuid", "created_by"),
                                        new DataAttribute("region", "区域", "string", "region")))),
                        ResourceManifest.PLATFORM_REALM));
        admin = (String) login("operator", SUPER_ADMIN_PASSWORD).get("accessToken");
    }

    @Test
    void superAdminIsBuiltinAndPlatformPermissionsAreSeparateFromTenantPermissions() {
        // 签发时按数据库发布版本与明细：超级管理员拥有全部有效平台权限码，在全部平台数据资源上为「全部」
        long version = version(SUPER_ADMIN_ID);
        assertThat(NimbusJwtDecoder.withJwkSetUri(baseUrl() + "/platform/oauth2/jwks")
                        .build()
                        .decode(admin)
                        .<Number>getClaim("ver")
                        .longValue())
                .isEqualTo(version);
        MembershipGrants grants = grants(SUPER_ADMIN_ID);
        assertThat(grants.roles()).containsExactly("super-admin");
        assertThat(grants.permissions())
                .contains(
                        "platform:operator:manage",
                        "platform:role:manage",
                        "platform:tenant:read",
                        "platform:tenant:manage")
                .noneMatch(code -> code.startsWith("iam:"));
        assertThat(grants.dataPolicies()).containsEntry("tenant", List.of(DataScope.ALL.template()));

        Map<?, ?> access = data(call(admin, HttpMethod.GET, "/platform/access", null));
        assertThat((List<Object>) access.get("roles")).containsExactly("super-admin");
        assertThat((List<Object>) access.get("permissions")).contains("platform:tenant:read");

        // 两个 Realm 的权限码互不可见：租户角色与套餐看不到平台权限码，平台角色看不到租户权限码
        assertThat(permissions.activePermissions())
                .extracting(PermissionRow::code)
                .contains("iam:role:manage")
                .noneMatch(code -> code.startsWith("platform:"));
        assertThat(permissions.planScopedPermissions())
                .extracting(PermissionRow::code)
                .noneMatch(code -> code.startsWith("platform:"));
        assertThat(permissions.currentDataResources())
                .noneMatch(current -> current.resource().code().equals("tenant"));
        List<?> grantable = list(call(admin, HttpMethod.GET, "/platform/roles/permissions", null));
        assertThat(grantable)
                .<Object>extracting(item -> ((Map<?, ?>) item).get("code"))
                .contains("platform:operator:manage", "platform:tenant:read")
                .doesNotContain("iam:role:manage", "iam:member:manage");
        assertThat(code(post(admin, "/platform/roles", role("leaky", List.of("iam:role:manage"), List.of()))))
                .isEqualTo("platformRole.invalidPermission");

        Map<?, ?> resource = (Map<?, ?>) list(call(admin, HttpMethod.GET, "/platform/roles/data-resources", null))
                .get(0);
        assertThat(resource.get("code")).isEqualTo("tenant");
        // 租户没有归属部门，部门类预设不可用
        assertThat((List<Object>) resource.get("scopes")).containsExactly("self", "all");
        assertThat(code(post(
                        admin,
                        "/platform/roles",
                        role("dept", List.of(), List.of(Map.of("resource", "tenant", "scope", "department"))))))
                .isEqualTo("role.invalidDataPolicy");
        assertThat(code(post(
                        admin,
                        "/platform/roles",
                        role(
                                "bad-condition",
                                List.of(),
                                List.of(Map.of(
                                        "resource",
                                        "tenant",
                                        "scope",
                                        "custom",
                                        "condition",
                                        Map.of("attr", "resource.amount", "op", "gt", "value", 1)))))))
                .isEqualTo("role.invalidDataCondition");

        // 超级管理员是唯一的预置角色，不能占用其编码、修改或删除
        Map<?, ?> superAdmin = (Map<?, ?>)
                list(call(admin, HttpMethod.GET, "/platform/roles", null)).get(0);
        assertThat(superAdmin.get("code")).isEqualTo("super-admin");
        assertThat(superAdmin.get("builtin")).isEqualTo(true);
        String superAdminRole = (String) superAdmin.get("id");
        assertThat(code(post(admin, "/platform/roles", role("super-admin", List.of(), List.of()))))
                .isEqualTo("platformRole.codeTaken");
        assertThat(code(call(
                        admin,
                        HttpMethod.PUT,
                        "/platform/roles/" + superAdminRole,
                        Map.of("name", "改名", "permissions", List.of()))))
                .isEqualTo("platformRole.builtin");
        assertThat(code(call(admin, HttpMethod.DELETE, "/platform/roles/" + superAdminRole, null)))
                .isEqualTo("platformRole.builtin");
    }

    @Test
    void restrictedOperatorGetsItsRoleGrantsAndChangesTakeEffectImmediately() {
        String roleId = (String) data(post(
                        admin,
                        "/platform/roles",
                        role(
                                "east-viewer",
                                List.of("platform:tenant:read"),
                                List.of(Map.of(
                                        "resource",
                                        "tenant",
                                        "scope",
                                        "custom",
                                        "condition",
                                        JSON.readTree(REGION_CONDITION))))))
                .get("id");
        ResponseEntity<Map> created = post(admin, "/platform/operators", operator("east-ops", "east", roleId));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        UUID east = UUID.fromString((String) data(created).get("id"));
        assertThat(data(created)).containsEntry("region", "east").containsEntry("status", "active");
        assertThat(code(post(admin, "/platform/operators", operator("east-ops", null, roleId))))
                .isEqualTo("operator.accountTaken");

        Map<?, ?> tokens = login("east-ops", "East-password-123!");
        String eastToken = (String) tokens.get("accessToken");
        MembershipGrants grants = grants(east);
        assertThat(grants.roles()).containsExactly("east-viewer");
        assertThat(grants.permissions()).containsExactly("platform:tenant:read");
        assertThat(grants.subject().attributes()).containsEntry("region", "east");
        assertThat(grants.subject().attributeTypes()).containsEntry("region", "string");
        assertThat(grants.dataPolicies().get("tenant")).hasSize(1);
        assertThat(JSON.readTree(grants.dataPolicies().get("tenant").get(0)))
                .isEqualTo(JSON.readTree(REGION_CONDITION));

        // 没有管理权限码的 Operator 不能管理账号与角色
        assertThat(call(eastToken, HttpMethod.GET, "/platform/operators", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(code(call(eastToken, HttpMethod.GET, "/platform/roles", null)))
                .isEqualTo("auth.forbidden");

        // 授权变更：持有者版本递增，旧 Token 在网关失效，刷新后按新权限签发；iam 自身的判断按数据库立即生效
        long before = version(east);
        assertThat(call(
                                admin,
                                HttpMethod.PUT,
                                "/platform/roles/" + roleId,
                                role(
                                        "east-viewer",
                                        List.of("platform:tenant:read", "platform:operator:manage"),
                                        List.of()))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(version(east)).isGreaterThan(before);
        assertThat(grants(east).permissions()).contains("platform:operator:manage");
        assertThat(grants(east).dataPolicies()).doesNotContainKey("tenant");
        assertThat(call(eastToken, HttpMethod.GET, "/platform/operators", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        // 管理 Operator 账号时可读取平台角色列表以便分配，但不能修改角色
        assertThat(call(eastToken, HttpMethod.GET, "/platform/roles", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(code(call(eastToken, HttpMethod.GET, "/platform/roles/permissions", null)))
                .isEqualTo("auth.forbidden");
        // 只有超级管理员能分配超级管理员、操作超级管理员的账号，否则管理账号的权限即可提权
        String superAdminRole = (String) ((Map<?, ?>) list(call(admin, HttpMethod.GET, "/platform/roles", null))
                        .get(0))
                .get("id");
        assertThat(code(call(
                        eastToken,
                        HttpMethod.PUT,
                        "/platform/operators/" + east + "/roles",
                        Map.of("roleIds", List.of(superAdminRole)))))
                .isEqualTo("auth.forbidden");
        assertThat(code(call(
                        eastToken,
                        HttpMethod.PUT,
                        "/platform/operators/" + SUPER_ADMIN_ID + "/password",
                        Map.of("password", "Taken-over-123!"))))
                .isEqualTo("auth.forbidden");
        assertThat(code(call(eastToken, HttpMethod.POST, "/platform/operators/" + SUPER_ADMIN_ID + "/disable", null)))
                .isEqualTo("auth.forbidden");

        // 负责区域是主体属性，变化后同样递增版本
        before = version(east);
        call(admin, HttpMethod.PUT, "/platform/operators/" + east, Map.of("displayName", "华南运营", "region", "south"));
        assertThat(version(east)).isGreaterThan(before);
        assertThat(grants(east).subject().attributes()).containsEntry("region", "south");

        // 角色变更：撤销后版本递增，iam 立即拒绝
        before = version(east);
        call(admin, HttpMethod.PUT, "/platform/operators/" + east + "/roles", Map.of("roleIds", List.of()));
        assertThat(version(east)).isGreaterThan(before);
        assertThat(grants(east).permissions()).isEmpty();
        assertThat(call(eastToken, HttpMethod.GET, "/platform/operators", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);

        // 停用：版本递增，不能再登录或刷新；恢复后可重新登录
        before = version(east);
        assertThat(call(admin, HttpMethod.POST, "/platform/operators/" + east + "/disable", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(version(east)).isGreaterThan(before);
        assertThat(loginStatus("east-ops", "East-password-123!")).isEqualTo(401);
        assertThat(refreshStatus((String) tokens.get("refreshToken"))).isEqualTo(401);
        call(admin, HttpMethod.POST, "/platform/operators/" + east + "/enable", null);
        assertThat(loginStatus("east-ops", "East-password-123!")).isEqualTo(200);

        // 重置密码：旧密码失效
        before = version(east);
        call(
                admin,
                HttpMethod.PUT,
                "/platform/operators/" + east + "/password",
                Map.of("password", "New-password-456!"));
        assertThat(version(east)).isGreaterThan(before);
        assertThat(loginStatus("east-ops", "East-password-123!")).isEqualTo(401);
        assertThat(loginStatus("east-ops", "New-password-456!")).isEqualTo(200);

        List<?> operators = list(call(admin, HttpMethod.GET, "/platform/operators", null));
        assertThat(operators)
                .filteredOn(item -> "east-ops".equals(((Map<?, ?>) item).get("account")))
                .singleElement()
                .satisfies(item -> assertThat((Map<Object, Object>) item)
                        .containsEntry("displayName", "华南运营")
                        .containsEntry("region", "south")
                        .containsEntry("roleIds", List.of())
                        .doesNotContainKey("passwordHash"));

        // 仍有持有者的角色不能删除
        call(admin, HttpMethod.PUT, "/platform/operators/" + east + "/roles", Map.of("roleIds", List.of(roleId)));
        assertThat(code(call(admin, HttpMethod.DELETE, "/platform/roles/" + roleId, null)))
                .isEqualTo("platformRole.inUse");
        call(admin, HttpMethod.PUT, "/platform/operators/" + east + "/roles", Map.of("roleIds", List.of()));
        assertThat(call(admin, HttpMethod.DELETE, "/platform/roles/" + roleId, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
    }

    @Test
    void lastActiveSuperAdminCannotBeDisabledOrUnassigned() {
        assertThat(code(call(admin, HttpMethod.POST, "/platform/operators/" + SUPER_ADMIN_ID + "/disable", null)))
                .isEqualTo("platformRole.lastSuperAdmin");
        assertThat(code(call(
                        admin,
                        HttpMethod.PUT,
                        "/platform/operators/" + SUPER_ADMIN_ID + "/roles",
                        Map.of("roleIds", List.of()))))
                .isEqualTo("platformRole.lastSuperAdmin");
        assertThat(grants(SUPER_ADMIN_ID).roles()).containsExactly("super-admin");

        // 另有超级管理员时可以停用其中一位
        String superAdminRole = (String) ((Map<?, ?>) list(call(admin, HttpMethod.GET, "/platform/roles", null))
                        .get(0))
                .get("id");
        UUID backup = UUID.fromString(
                (String) data(post(admin, "/platform/operators", operator("backup-admin", null, superAdminRole)))
                        .get("id"));
        assertThat(call(admin, HttpMethod.POST, "/platform/operators/" + backup + "/disable", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
    }

    private static Map<String, Object> role(String code, List<String> codes, List<Map<String, Object>> policies) {
        return Map.of("code", code, "name", code, "permissions", codes, "dataPolicies", policies);
    }

    private static Map<String, Object> operator(String account, String region, String roleId) {
        var body = new HashMap<String, Object>(Map.of(
                "account",
                account,
                "displayName",
                account,
                "password",
                "East-password-123!",
                "roleIds",
                List.of(roleId)));
        if (region != null) body.put("region", region);
        return body;
    }

    private long version(UUID operator) {
        return Long.parseLong(redisTemplate.opsForValue().get(OperatorStateKeys.version(operator)));
    }

    // 按 Platform Realm 服务的方式读取：SDK 的明细模型直接反序列化 iam 发布的 JSON。
    private MembershipGrants grants(UUID operator) {
        String value = redisTemplate.opsForValue().get(OperatorStateKeys.grants(operator, version(operator)));
        return JSON.readValue(value, MembershipGrants.class);
    }

    private ResponseEntity<Map> post(String token, String uri, Object body) {
        return call(token, HttpMethod.POST, uri, body);
    }

    private ResponseEntity<Map> call(String token, HttpMethod method, String uri, Object body) {
        var request =
                RestClient.create(baseUrl()).method(method).uri(uri).headers(headers -> headers.setBearerAuth(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    private static Object code(ResponseEntity<Map> response) {
        return response.getBody().get("code");
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> data(ResponseEntity<Map> response) {
        assertThat(response.getBody().get("code")).isEqualTo("ok");
        return (Map<Object, Object>) response.getBody().get("data");
    }

    private static List<?> list(ResponseEntity<Map> response) {
        assertThat(response.getBody().get("code")).isEqualTo("ok");
        return (List<?>) response.getBody().get("data");
    }

    // 走完整的登录、授权码与换发流程，验证签发时写入的版本与发布的明细。
    private Map<?, ?> login(String account, String password) {
        var login = noRedirectClient()
                .post()
                .uri("/platform/auth/login")
                .body(Map.of("account", account, "password", password))
                .retrieve()
                .toEntity(Map.class);
        String cookie = login.getHeaders().getFirst("Set-Cookie").split(";")[0];
        Map<?, ?> exchanged = RestClient.create(baseUrl())
                .post()
                .uri("/platform/auth/exchange")
                .body(Map.of("code", authorize(cookie), "codeVerifier", VERIFIER))
                .retrieve()
                .body(Map.class);
        return (Map<?, ?>) exchanged.get("data");
    }

    private int loginStatus(String account, String password) {
        return noRedirectClient()
                .post()
                .uri("/platform/auth/login")
                .body(Map.of("account", account, "password", password))
                .exchange((req, response) -> response.getStatusCode().value());
    }

    private int refreshStatus(String refreshToken) {
        return noRedirectClient()
                .post()
                .uri("/platform/auth/refresh")
                .body(Map.of("refreshToken", refreshToken))
                .exchange((req, response) -> response.getStatusCode().value());
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
                            .queryParam("client_id", "platform-console")
                            .queryParam("redirect_uri", "http://localhost:5173/")
                            .queryParam("scope", "platform")
                            .queryParam("state", "test-state")
                            .queryParam("code_challenge_method", "S256")
                            .queryParam("code_challenge", challenge)
                            .build())
                    .header("Cookie", cookie)
                    .retrieve()
                    .toBodilessEntity();
            return UriComponentsBuilder.fromUri(response.getHeaders().getLocation())
                    .build()
                    .getQueryParams()
                    .getFirst("code");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private RestClient noRedirectClient() {
        return RestClient.builder()
                .baseUrl(baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
