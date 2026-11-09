package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.plan.service.PlanService;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.DataAttribute;
import io.saasforge.sdk.core.ResourceManifest.DataResource;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;

/** 保存角色权限与 Data Policy 前的影响预览。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/role-impact.sql")
class RoleImpactPreviewTest {
    static final String KEY_FILE = "target/test-role-impact-signing.jwk";
    static final UUID TENANT_A = UUID.fromString("019a0120-0000-7000-8000-00000000000a");
    static final UUID ADMIN = UUID.fromString("019a0120-0000-7000-8000-000000000201");
    static final UUID SOLO = UUID.fromString("019a0120-0000-7000-8000-000000000202");
    static final UUID DUAL = UUID.fromString("019a0120-0000-7000-8000-000000000203");
    static final UUID BUILTIN = UUID.fromString("019a0120-0000-7000-8000-000000000204");
    static final UUID SUSPENDED = UUID.fromString("019a0120-0000-7000-8000-000000000205");
    static final UUID INVITED = UUID.fromString("019a0120-0000-7000-8000-000000000206");
    static final UUID PLAIN = UUID.fromString("019a0120-0000-7000-8000-000000000207");
    static final UUID TENANT_B = UUID.fromString("019a0120-0000-7000-8000-00000000000b");
    static final UUID ADMIN_B = UUID.fromString("019a0120-0000-7000-8000-000000000208");
    static final UUID TENANT_C = UUID.fromString("019a0120-0000-7000-8000-00000000000c");
    static final UUID ADMIN_C = UUID.fromString("019a0120-0000-7000-8000-000000000209");
    static final UUID MEMBER_C = UUID.fromString("019a0120-0000-7000-8000-00000000020a");

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
    PermissionService permissions;

    @Autowired
    PlanService plans;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void reportManifest() {
        permissions.report(
                "impact-service",
                new ResourceManifest(
                        "impact-service",
                        "1",
                        List.of(
                                new Permission("impact:read", "查看", "影响"),
                                new Permission("impact:write", "编辑", "影响"),
                                new Permission("impact:outside", "套餐外", "影响")),
                        List.of()));
    }

    @Test
    void permissionPreviewCountsOnlyMembersWhoNetLoseACode() throws Exception {
        String target = createRole(ADMIN, "net-target", List.of("impact:read", "impact:write"));
        String other = createRole(ADMIN, "net-other", List.of("impact:read"));
        assign(ADMIN, SOLO, List.of(target));
        assign(ADMIN, DUAL, List.of(target, other));
        assign(ADMIN, BUILTIN, List.of(target, roleId(ADMIN, "tenant-admin")));
        assign(ADMIN, SUSPENDED, List.of(target));
        assign(ADMIN, INVITED, List.of(target));

        var dropRead = permissionImpact(ADMIN, target, List.of("impact:write"));
        assertThat(dropRead.getStatusCode().value()).isEqualTo(200);
        // DUAL 经另一角色仍持有 impact:read，BUILTIN 的预置角色拥有全部权限，停用与待接受邀请的成员不统计。
        assertThat(data(dropRead).get("affectedCount")).isEqualTo(1);
        assertThat(memberIds(dropRead)).containsExactly(SOLO.toString());
        assertThat(data(dropRead).get("lostPermissions")).isEqualTo(List.of("impact:read"));
        assertThat(((List<Map<String, Object>>) data(dropRead).get("members")).get(0))
                .containsEntry("account", "impactsolo")
                .containsEntry("displayName", "仅目标角色");

        var dropAll = permissionImpact(ADMIN, target, List.of());
        assertThat(data(dropAll).get("affectedCount")).isEqualTo(2);
        assertThat(memberIds(dropAll)).containsExactly(SOLO.toString(), DUAL.toString());
        assertThat(data(dropAll).get("lostPermissions")).isEqualTo(List.of("impact:read", "impact:write"));

        // 只增加权限码时没有成员失去权限。
        var grantOnly = permissionImpact(ADMIN, target, List.of("impact:read", "impact:write", "impact:outside"));
        assertThat(data(grantOnly).get("affectedCount")).isEqualTo(0);
        assertThat(memberIds(grantOnly)).isEmpty();
        assertThat(data(grantOnly).get("lostPermissions")).isEqualTo(List.of());
    }

    @Test
    void codesAlreadyOutsideThePlanAreNotCountedAsLost() throws Exception {
        String target = createRole(ADMIN_C, "plan-target", List.of("impact:read", "impact:outside"));
        assign(ADMIN_C, MEMBER_C, List.of(target));
        plans.apply(new TenantPlanChanged(
                TENANT_C, UUID.randomUUID(), "basic", List.of("impact:read"), List.of(), null, null, 1));

        var impact = permissionImpact(ADMIN_C, target, List.of());
        assertThat(data(impact).get("affectedCount")).isEqualTo(1);
        assertThat(memberIds(impact)).containsExactly(MEMBER_C.toString());
        assertThat(data(impact).get("lostPermissions")).isEqualTo(List.of("impact:read"));

        // 提交 Plan 外的权限码与真实修改一样被拒绝。
        assertThat(code(permissionImpact(ADMIN_C, target, List.of("impact:outside"))))
                .isEqualTo("role.permissionNotInPlan");
    }

    @Test
    void permissionPreviewListsTheFirstTwentyMembersButCountsAll() throws Exception {
        String target = createRole(ADMIN_B, "bulk-target", List.of("impact:read"));
        List<String> ids = IntStream.rangeClosed(1, 25)
                .mapToObj(n -> "019a0120-0000-7000-8000-000000002%03x".formatted(n))
                .toList();
        for (String id : ids) assign(ADMIN_B, UUID.fromString(id), List.of(target));

        var impact = permissionImpact(ADMIN_B, target, List.of());
        assertThat(data(impact).get("affectedCount")).isEqualTo(25);
        assertThat(memberIds(impact)).containsExactlyElementsOf(ids.subList(0, 20));
    }

    @Test
    void dataPolicyPreviewReturnsActiveHoldersOnly() throws Exception {
        reportLedger();
        String target = createRole(ADMIN, "policy-target", List.of());
        assign(ADMIN, SOLO, List.of(target));
        assign(ADMIN, BUILTIN, List.of(target, roleId(ADMIN, "tenant-admin")));
        assign(ADMIN, SUSPENDED, List.of(target));
        assign(ADMIN, INVITED, List.of(target));

        var impact = dataPolicyImpact(ADMIN, target, List.of(Map.of("resource", "ledger", "scope", "all")));
        assertThat(impact.getStatusCode().value()).isEqualTo(200);
        assertThat(data(impact).get("affectedCount")).isEqualTo(2);
        assertThat(memberIds(impact)).containsExactly(SOLO.toString(), BUILTIN.toString());
        assertThat(data(impact)).containsOnlyKeys("affectedCount", "members");
    }

    @Test
    void previewsWriteNothingAndDoNotBumpTheMembershipVersion() throws Exception {
        reportLedger();
        String target = createRole(ADMIN, "readonly-target", List.of("impact:read"));
        assign(ADMIN, SOLO, List.of(target));
        long before = version(SOLO);

        assertThat(permissionImpact(ADMIN, target, List.of()).getStatusCode().value())
                .isEqualTo(200);
        assertThat(dataPolicyImpact(ADMIN, target, List.of(Map.of("resource", "ledger", "scope", "all")))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        assertThat(version(SOLO)).isEqualTo(before);
        Map<?, ?> role = role(ADMIN, target);
        assertThat(role.get("permissions")).isEqualTo(List.of("impact:read"));
        assertThat(role.get("dataPolicies")).isEqualTo(List.of());

        // 真实修改会递增版本，说明上面的断言确实观察得到变化。
        var replace =
                call(ADMIN, HttpMethod.PUT, "/roles/" + target + "/permissions", Map.of("permissions", List.of()));
        assertThat(replace.getStatusCode().value()).isEqualTo(200);
        assertThat(version(SOLO)).isGreaterThan(before);
    }

    @Test
    void previewsRequireTheSamePermissionAndValidationAsTheUpdateAndRejectOtherTenants() throws Exception {
        reportLedger();
        String target = createRole(ADMIN, "access-target", List.of("impact:read"));
        List<Map<String, String>> policies = List.of(Map.of("resource", "ledger", "scope", "all"));

        assertThat(permissionImpact(PLAIN, target, List.of()).getStatusCode().value())
                .isEqualTo(403);
        assertThat(dataPolicyImpact(PLAIN, target, policies).getStatusCode().value())
                .isEqualTo(403);

        String manager = createRole(ADMIN, "access-manager", List.of("iam:role:manage"));
        assign(ADMIN, PLAIN, List.of(manager));
        assertThat(permissionImpact(PLAIN, target, List.of()).getStatusCode().value())
                .isEqualTo(200);
        assertThat(dataPolicyImpact(PLAIN, target, policies).getStatusCode().value())
                .isEqualTo(200);

        var crossTenant = permissionImpact(ADMIN_B, target, List.of());
        assertThat(crossTenant.getStatusCode().value()).isEqualTo(404);
        assertThat(code(crossTenant)).isEqualTo("role.notFound");
        assertThat(code(dataPolicyImpact(ADMIN_B, target, policies))).isEqualTo("role.notFound");

        String builtin = roleId(ADMIN, "tenant-admin");
        assertThat(code(permissionImpact(ADMIN, builtin, List.of()))).isEqualTo("role.builtin");
        assertThat(code(dataPolicyImpact(ADMIN, builtin, policies))).isEqualTo("role.builtin");

        assertThat(code(permissionImpact(ADMIN, target, List.of("impact:unknown"))))
                .isEqualTo("role.invalidPermission");
        List<Map<String, String>> invalid = List.of(Map.of("resource", "unknown", "scope", "all"));
        var rejected = dataPolicyImpact(ADMIN, target, invalid);
        var update = call(ADMIN, HttpMethod.PUT, "/roles/" + target + "/data-policies", Map.of("policies", invalid));
        assertThat(rejected.getStatusCode().value())
                .isEqualTo(update.getStatusCode().value())
                .isGreaterThanOrEqualTo(400);
        assertThat(code(rejected)).isEqualTo(code(update));
    }

    private void reportLedger() {
        permissions.report(
                "impact-ledger",
                new ResourceManifest(
                        "impact-ledger",
                        "1",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(new DataResource(
                                "ledger",
                                "台账",
                                "ledgers",
                                List.of(new DataAttribute("ownerId", "归属成员", "uuid", "owner_id"))))));
    }

    private ResponseEntity<Map> dataPolicyImpact(UUID actor, String roleId, List<Map<String, String>> policies)
            throws Exception {
        return call(actor, HttpMethod.POST, "/roles/" + roleId + "/data-policies/impact", Map.of("policies", policies));
    }

    private Map<?, ?> role(UUID actor, String id) throws Exception {
        for (Object item :
                (List<?>) call(actor, HttpMethod.GET, "/roles", null).getBody().get("data")) {
            if (id.equals(((Map<?, ?>) item).get("id"))) return (Map<?, ?>) item;
        }
        throw new AssertionError("Role not found: " + id);
    }

    private long version(UUID membership) {
        String value = redisTemplate.opsForValue().get(MembershipStateKeys.version(TENANT_A, membership));
        return value == null ? 1 : Long.parseLong(value);
    }

    private static Object code(ResponseEntity<Map> response) {
        return response.getBody().get("code");
    }

    private ResponseEntity<Map> permissionImpact(UUID actor, String roleId, List<String> codes) throws Exception {
        return call(actor, HttpMethod.POST, "/roles/" + roleId + "/permissions/impact", Map.of("permissions", codes));
    }

    private String createRole(UUID actor, String code, List<String> codes) throws Exception {
        var response = call(actor, HttpMethod.POST, "/roles", Map.of("code", code, "name", code, "permissions", codes));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return (String) data(response).get("id");
    }

    private void assign(UUID actor, UUID membership, List<String> roleIds) throws Exception {
        var response = call(actor, HttpMethod.PUT, "/roles/memberships/" + membership, Map.of("roleIds", roleIds));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private String roleId(UUID actor, String code) throws Exception {
        for (Object item :
                (List<?>) call(actor, HttpMethod.GET, "/roles", null).getBody().get("data")) {
            var role = (Map<?, ?>) item;
            if (code.equals(role.get("code"))) return (String) role.get("id");
        }
        throw new AssertionError("Role not found: " + code);
    }

    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private static List<Object> memberIds(ResponseEntity<Map> response) {
        return ((List<Map<String, Object>>) data(response).get("members"))
                .stream().map(member -> member.get("membershipId")).toList();
    }

    private ResponseEntity<Map> call(UUID membership, HttpMethod method, String uri, Object body) throws Exception {
        String token = token(membership);
        var request = RestClient.create("http://127.0.0.1:" + port)
                .method(method)
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    // 以 iam 自己的签名密钥签发 Access Token，省去完整的登录流程；Membership 与 Identity 的标识按 fixture 约定对应。
    private static String token(UUID membership) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(KEY_FILE)));
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8080/iam")
                .subject(identityOf(membership).toString())
                .audience("tenant-app")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", tenantOf(membership).toString())
                .claim("membership_id", membership.toString())
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static UUID identityOf(UUID membership) {
        return UUID.fromString(membership.toString().replaceAll("0002(..)$", "0001$1"));
    }

    private static UUID tenantOf(UUID membership) {
        if (membership.equals(ADMIN_B)) return TENANT_B;
        return membership.equals(ADMIN_C) || membership.equals(MEMBER_C) ? TENANT_C : TENANT_A;
    }
}
