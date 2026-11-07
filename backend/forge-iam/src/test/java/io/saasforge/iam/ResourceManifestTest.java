package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.membership.service.MembershipStateService;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.core.MembershipStateKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 资源清单上报、Permission Code 失效、预置角色与按权限裁剪的菜单（工单 12）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/manifest_roles.sql")
class ResourceManifestTest {
    static final String KEY_FILE = "target/test-manifest-signing.jwk";
    static final String TOKEN = "test-demo-manifest-token";
    static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    static final UUID OWNER = UUID.fromString("019a0110-0000-7000-8000-000000000003");
    static final UUID ADMIN = UUID.fromString("019a0110-0000-7000-8000-0000000000a3");
    static final UUID PLAIN = UUID.fromString("019a0110-0000-7000-8000-0000000000a4");
    static final UUID UNPROVISIONED_TENANT = UUID.fromString("019a0110-0000-7000-8000-0000000000a5");

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
        registry.add("forge.iam.manifest.tokens.demo-service", () -> TOKEN);
        registry.add("forge.iam.manifest.tokens.other-service", () -> "test-other-manifest-token");
    }

    @LocalServerPort
    int port;

    @Autowired
    MembershipStateService membershipStates;

    @Autowired
    RoleService roles;

    @Autowired
    DataSource dataSource;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void serviceDirectoryContainsOnlyReportedServicesAndRequiresAServiceToken() {
        assertThat(report(TOKEN, manifest("ops-directory", List.of("order:read")))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
        var response = client().get()
                .uri("/internal/services")
                .headers(headers -> headers.setBearerAuth(TOKEN))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, result) -> {})
                .toEntity(Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List<String>) response.getBody().get("data"))
                .contains("demo-service", "forge-iam")
                .doesNotContain("other-service", "forge-iam-platform");
        var rejected = client().get()
                .uri("/internal/services")
                .headers(headers -> headers.setBearerAuth("wrong-token"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, result) -> {})
                .toEntity(Map.class);
        assertThat(rejected.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void manifestVersionsAreImmutableAndOnlyTheOwningServiceCanReport() {
        Map<String, Object> v1 = manifest("1", List.of("order:read", "order:approve"));
        assertThat(report(TOKEN, v1).getStatusCode().value()).isEqualTo(201);
        // 多实例或重启时重复上报同一版本是无操作。
        assertThat(report(TOKEN, v1).getStatusCode().value()).isEqualTo(200);

        var changed = report(TOKEN, manifest("1", List.of("order:read")));
        assertThat(changed.getStatusCode().value()).isEqualTo(409);
        assertThat(changed.getBody().get("code")).isEqualTo("manifest.versionConflict");

        assertThat(report("wrong-token", manifest("9", List.of()))
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        var mismatch = report("test-other-manifest-token", manifest("9", List.of()));
        assertThat(mismatch.getBody().get("code")).isEqualTo("manifest.serviceMismatch");

        var taken = report(
                "test-other-manifest-token",
                Map.of(
                        "service",
                        "other-service",
                        "version",
                        "1",
                        "permissions",
                        List.of(Map.of("code", "order:read", "name", "抢占")),
                        "menus",
                        List.of()));
        assertThat(taken.getStatusCode().value()).isEqualTo(409);
        assertThat(taken.getBody().get("code")).isEqualTo("manifest.permissionConflict");
    }

    @Test
    @Sql("/fixtures/legacy_manifest.sql")
    void storedManifestWithoutActionsCanBeReportedAgainAtTheSameVersion() {
        Map<String, Object> legacy = Map.of(
                "service",
                "demo-service",
                "version",
                "legacy-actions",
                "permissions",
                List.of(Map.of("code", "order:read", "name", "查看订单")),
                "menus",
                List.of(Map.of(
                        "code", "legacy", "name", "旧菜单", "app", "demo", "path", "legacy", "permission", "order:read")));
        assertThat(report(TOKEN, legacy).getStatusCode().value()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"group", "unknown", "duplicate", "page"})
    void invalidMenuActionsAreRejectedOnReport(String violation) {
        Map<String, Object> page = new HashMap<>(
                Map.of("code", "orders", "name", "订单", "app", "demo", "path", "orders", "permission", "order:read"));
        page.put(
                "actions",
                switch (violation) {
                    case "unknown" -> List.of("order:typo");
                    case "duplicate" -> List.of("order:approve", "order:approve");
                    case "page" -> List.of("order:read");
                    default -> List.of("order:approve");
                });
        if (violation.equals("group")) {
            page.remove("app");
            page.remove("path");
            page.put("children", List.of(Map.of("code", "child", "name", "子菜单", "app", "demo", "path", "child")));
        }
        Map<String, Object> body =
                new HashMap<>(manifest("invalid-actions-" + violation, List.of("order:read", "order:approve")));
        body.put("menus", List.of(page));
        var response = report(TOKEN, body);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code")).isEqualTo("manifest.invalid");
    }

    @Test
    void dictionariesAndParametersAreStoredWithTheManifestVersion() {
        Map<String, Object> body = withReferenceData("reference-data", "PT30M");
        assertThat(report(TOKEN, body).getStatusCode().value()).isEqualTo(201);
        assertThat(report(TOKEN, body).getStatusCode().value()).isEqualTo(200);

        // 只改参数默认值也是内容变化，沿用同一版本被拒绝。
        var changed = report(TOKEN, withReferenceData("reference-data", "PT45M"));
        assertThat(changed.getStatusCode().value()).isEqualTo(409);
        assertThat(changed.getBody().get("code")).isEqualTo("manifest.versionConflict");
    }

    // 与 SDK 生成时共用 ResourceManifest.validate()；ResourceManifestCollectorTest 覆盖生成一侧的同样规则。
    @ParameterizedTest
    @ValueSource(
            strings = {
                "duplicate-dictionary",
                "duplicate-parameter",
                "custom-prefix",
                "duplicate-item-name",
                "default-out-of-range",
                "default-too-long",
                "default-not-item",
                "unknown-dictionary"
            })
    void invalidDictionariesAndParametersAreRejectedOnReport(String violation) {
        Map<String, Object> dictionary = dictionary(List.of(item("online", "线上"), item("referral", "转介绍")));
        List<Map<String, Object>> dictionaries = List.of(dictionary);
        List<Map<String, Object>> parameters = List.of(duration("PT30M"));
        switch (violation) {
            case "duplicate-dictionary" -> dictionaries = List.of(dictionary, dictionary);
            case "duplicate-parameter" -> parameters = List.of(duration("PT30M"), duration("PT30M"));
            case "custom-prefix" -> dictionaries = List.of(dictionary(List.of(item("custom.vip", "大客户"))));
            case "duplicate-item-name" ->
                dictionaries = List.of(dictionary(List.of(item("online", "线上"), item("web", "线上"))));
            case "default-out-of-range" -> parameters = List.of(duration("P5D"));
            case "default-too-long" ->
                parameters = List.of(Map.of(
                        "code", "order.remark", "name", "备注", "type", "string", "default", "abcdef", "maxLength", 5));
            case "default-not-item" -> parameters = List.of(dictionaryParameter("customer-source", "offline"));
            default -> parameters = List.of(dictionaryParameter("customer-sources", "online"));
        }
        Map<String, Object> body = new HashMap<>(manifest("invalid-reference-" + violation, List.of("order:read")));
        body.put("dictionaries", dictionaries);
        body.put("parameters", parameters);
        var response = report(TOKEN, body);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code")).isEqualTo("manifest.invalid");
    }

    @Test
    void sharedActionsAreAcceptedButNotExposedToTheShell() throws Exception {
        Map<String, Object> body = new HashMap<>(manifest("shared-actions", List.of("order:read", "order:approve")));
        body.put(
                "menus",
                List.of(Map.of(
                        "code",
                        "group",
                        "name",
                        "分组",
                        "children",
                        List.of(
                                Map.of(
                                        "code",
                                        "page-a",
                                        "name",
                                        "页面甲",
                                        "app",
                                        "demo",
                                        "path",
                                        "a",
                                        "permission",
                                        "order:read",
                                        "actions",
                                        List.of("order:approve")),
                                Map.of(
                                        "code",
                                        "page-b",
                                        "name",
                                        "页面乙",
                                        "app",
                                        "demo",
                                        "path",
                                        "b",
                                        "permission",
                                        "order:read",
                                        "actions",
                                        List.of("order:approve")),
                                Map.of(
                                        "code",
                                        "page-c",
                                        "name",
                                        "页面丙",
                                        "app",
                                        "demo",
                                        "path",
                                        "c",
                                        "permission",
                                        "order:approve")))));
        assertThat(report(TOKEN, body).getStatusCode().value()).isEqualTo(201);
        Map<?, ?> access = access(ADMIN);
        assertThat(menuCodes(access)).containsExactly("group", "page-a", "page-b", "page-c");
        assertThat(JsonMapper.builder().build().writeValueAsString(access.get("menus")))
                .doesNotContain("actions");
        assertThat(List.<Object>copyOf((List<?>) access.get("permissions"))).contains("order:approve");
    }

    @Test
    void retiredPermissionCodesStopBeingGrantedAndComeBackWhenReportedAgain() throws Exception {
        report(TOKEN, manifest("retire-1", List.of("order:read", "order:approve")));
        assertThat(permissions(ADMIN)).contains("order:read", "order:approve");

        report(TOKEN, manifest("retire-2", List.of("order:read")));
        assertThat(permissions(ADMIN)).contains("order:read").doesNotContain("order:approve");

        report(TOKEN, manifest("retire-3", List.of("order:read", "order:approve")));
        assertThat(permissions(ADMIN)).contains("order:approve");
    }

    @Test
    void menusAreCroppedByTheMembershipPermissions() throws Exception {
        report(TOKEN, manifest("menus-1", List.of("order:read", "order:approve")));

        // Owner 与 Tenant Admin 都拥有全部有效权限码，看到完整菜单。
        for (UUID membership : List.of(OWNER, ADMIN)) {
            assertThat(menuCodes(access(membership)))
                    .containsExactly("home", "orders", "orders.list", "orders.approve");
        }
        // 没有权限的成员只看到无需权限的节点；子节点全部被隐藏的分组不展示。
        Map<?, ?> plain = access(PLAIN);
        assertThat((List<?>) plain.get("permissions")).isEmpty();
        assertThat(menuCodes(plain)).containsExactly("home");
    }

    @Test
    void publishedGrantsCarryAllActivePermissionsForBuiltinRoles() throws Exception {
        report(TOKEN, manifest("grants-1", List.of("order:read")));

        membershipStates.publish(TENANT, ADMIN, 1);
        Map<?, ?> grants = JsonMapper.builder()
                .build()
                .readValue(redisTemplate.opsForValue().get(MembershipStateKeys.grants(TENANT, ADMIN, 1)), Map.class);
        assertThat(List.<Object>copyOf((List<?>) grants.get("roles"))).containsExactly("tenant-admin");
        assertThat(List.<Object>copyOf((List<?>) grants.get("permissions"))).contains("order:read");

        membershipStates.publish(TENANT, PLAIN, 1);
        assertThat(redisTemplate.opsForValue().get(MembershipStateKeys.grants(TENANT, PLAIN, 1)))
                .contains("\"permissions\":[]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"second_owner", "forged_owner_flag", "delete_builtin", "unflag_builtin", "reserved_code"})
    void databaseRejectsWritesThatBreakOwnerUniquenessOrBuiltinRoles(String violation) throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> ScriptUtils.executeSqlScript(
                            connection, new ClassPathResource("fixtures/role-violations/" + violation + ".sql")))
                    .isInstanceOf(ScriptStatementFailedException.class);
        }
    }

    @Test
    void provisioningBuiltinRolesIsIdempotent() {
        roles.provisionBuiltinRoles(UNPROVISIONED_TENANT);
        roles.provisionBuiltinRoles(UNPROVISIONED_TENANT);
    }

    private static Map<String, Object> manifest(String version, List<String> codes) {
        return Map.of(
                "service",
                "demo-service",
                "version",
                version,
                "permissions",
                codes.stream().map(code -> Map.of("code", code, "name", code)).toList(),
                "menus",
                List.of(
                        Map.of("code", "home", "name", "首页", "app", "demo", "path", "home"),
                        Map.of(
                                "code", "orders",
                                "name", "订单",
                                "children",
                                        List.of(
                                                Map.of(
                                                        "code",
                                                        "orders.list",
                                                        "name",
                                                        "订单列表",
                                                        "app",
                                                        "demo",
                                                        "path",
                                                        "orders",
                                                        "permission",
                                                        "order:read"),
                                                Map.of(
                                                        "code",
                                                        "orders.approve",
                                                        "name",
                                                        "审批",
                                                        "app",
                                                        "demo",
                                                        "path",
                                                        "approvals",
                                                        "permission",
                                                        codes.contains("order:approve")
                                                                ? "order:approve"
                                                                : "order:read")))));
    }

    private static Map<String, Object> withReferenceData(String version, String autoClose) {
        Map<String, Object> body = new HashMap<>(manifest(version, List.of("order:read")));
        body.put("dictionaries", List.of(dictionary(List.of(item("online", "线上"), item("referral", "转介绍")))));
        body.put("parameters", List.of(duration(autoClose), dictionaryParameter("customer-source", "online")));
        return body;
    }

    private static Map<String, Object> dictionary(List<Map<String, Object>> items) {
        return Map.of("code", "customer-source", "name", "客户来源", "extensible", true, "items", items);
    }

    private static Map<String, Object> item(String code, String name) {
        return Map.of("code", code, "name", name);
    }

    private static Map<String, Object> duration(String defaultValue) {
        return Map.of(
                "code",
                "order.auto-close",
                "name",
                "未支付订单自动关闭时长",
                "type",
                "duration",
                "default",
                defaultValue,
                "min",
                "PT5M",
                "max",
                "P3D",
                "tenantEditable",
                true);
    }

    private static Map<String, Object> dictionaryParameter(String dictionary, String defaultValue) {
        return Map.of(
                "code",
                "customer.default-source",
                "name",
                "默认客户来源",
                "type",
                "dictionary",
                "dictionary",
                dictionary,
                "default",
                defaultValue);
    }

    private ResponseEntity<Map> report(String token, Map<String, Object> body) {
        return client().post()
                .uri("/internal/manifests")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(token))
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toEntity(Map.class);
    }

    private List<Object> permissions(UUID membership) throws Exception {
        return List.copyOf((List<?>) access(membership).get("permissions"));
    }

    private Map<?, ?> access(UUID membership) throws Exception {
        String token = token(membership);
        Map<?, ?> body = client().get()
                .uri("/auth/access")
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(Map.class);
        return (Map<?, ?>) body.get("data");
    }

    private static List<String> menuCodes(Map<?, ?> access) {
        List<String> codes = new ArrayList<>();
        collect((List<?>) access.get("menus"), codes);
        return codes;
    }

    private static void collect(List<?> nodes, List<String> codes) {
        for (Object node : nodes) {
            codes.add((String) ((Map<?, ?>) node).get("code"));
            Object children = ((Map<?, ?>) node).get("children");
            if (children != null) collect((List<?>) children, codes);
        }
    }

    // 以 iam 自己的签名密钥签发 Access Token，省去完整的登录与授权码流程；登录链路由 TenantLoginTest 覆盖。
    private static String token(UUID membership) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(KEY_FILE)));
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8080/iam")
                .subject("019a0110-0000-7000-8000-000000000002")
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

    private RestClient client() {
        return RestClient.create("http://127.0.0.1:" + port);
    }
}
