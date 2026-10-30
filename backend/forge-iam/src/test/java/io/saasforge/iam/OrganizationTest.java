package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.SubjectAttributes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/** Department 树、Position、Extended Attribute 与 Membership 分配，以及据此发布的主体属性（工单 13）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/organization.sql")
class OrganizationTest {
    static final String KEY_FILE = "target/test-organization-signing.jwk";
    static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-0000000000b1");
    static final UUID ADMIN = UUID.fromString("019a0110-0000-7000-8000-0000000000b3");
    static final UUID MEMBER = UUID.fromString("019a0110-0000-7000-8000-0000000000b5");
    static final UUID ADMIN_B = UUID.fromString("019a0110-0000-7000-8000-0000000000b7");
    static final Map<UUID, UUID> IDENTITIES = Map.of(
            ADMIN, UUID.fromString("019a0110-0000-7000-8000-0000000000b2"),
            MEMBER, UUID.fromString("019a0110-0000-7000-8000-0000000000b4"),
            ADMIN_B, UUID.fromString("019a0110-0000-7000-8000-0000000000b6"));

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
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> KEY_FILE);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void movingADepartmentRewritesThePathsOfItsWholeSubtree() throws Exception {
        String a = createDepartment(ADMIN, "总部", null);
        String b = createDepartment(ADMIN, "研发", a);
        String c = createDepartment(ADMIN, "平台组", b);
        String d = createDepartment(ADMIN, "分公司", null);
        assertThat(paths(ADMIN)).containsEntry(c, label(a) + "." + label(b) + "." + label(c));

        var moved = call(ADMIN, HttpMethod.PUT, "/org/departments/" + b + "/parent", Map.of("parentId", d));
        assertThat(moved.getStatusCode().value()).isEqualTo(200);
        Map<String, String> paths = paths(ADMIN);
        assertThat(paths)
                .containsEntry(a, label(a))
                .containsEntry(b, label(d) + "." + label(b))
                .containsEntry(c, label(d) + "." + label(b) + "." + label(c));

        // 移到自己的子树下会形成环，拒绝；移到根下则只剩自身 id。
        var cycle = call(ADMIN, HttpMethod.PUT, "/org/departments/" + d + "/parent", Map.of("parentId", c));
        assertThat(cycle.getStatusCode().value()).isEqualTo(400);
        assertThat(cycle.getBody().get("code")).isEqualTo("organization.invalidDepartmentMove");
        var toRoot = new HashMap<String, Object>();
        toRoot.put("parentId", null);
        call(ADMIN, HttpMethod.PUT, "/org/departments/" + b + "/parent", toRoot);
        assertThat(paths(ADMIN)).containsEntry(c, label(b) + "." + label(c));
    }

    @Test
    void subjectAttributesFollowTheMembershipAssignment() throws Exception {
        String root = createDepartment(ADMIN, "集团", null);
        String sales = createDepartment(ADMIN, "销售", root);
        String east = createDepartment(ADMIN, "华东", sales);
        String manager = (String) data(call(ADMIN, HttpMethod.POST, "/org/positions", Map.of("name", "经理")))
                .get("id");
        var level = call(
                ADMIN,
                HttpMethod.POST,
                "/org/extended-attributes",
                Map.of("code", "level", "name", "职级", "type", "number", "required", true));
        assertThat(level.getStatusCode().value()).isEqualTo(201);

        // 必填的扩展属性缺失、类型不符都被拒绝，分配不生效。
        var missing = assign(MEMBER, east, List.of(root), List.of(manager), Map.of());
        assertThat(missing.getBody().get("code")).isEqualTo("organization.invalidAttributeValue");
        var wrongType = assign(MEMBER, east, List.of(root), List.of(manager), Map.of("level", "高"));
        assertThat(wrongType.getStatusCode().value()).isEqualTo(400);

        long before = version(MEMBER);
        assertThat(assign(MEMBER, east, List.of(root), List.of(manager), Map.of("level", 3))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(version(MEMBER)).isGreaterThan(before);
        SubjectAttributes subject = subject(MEMBER);
        String eastPath = label(root) + "." + label(sales) + "." + label(east);
        assertThat(subject.departmentPaths()).containsExactlyInAnyOrder(eastPath, label(root));
        assertThat(subject.primaryDepartmentPath()).isEqualTo(eastPath);
        assertThat(subject.positionIds()).containsExactly(UUID.fromString(manager));
        assertThat(subject.attributes()).containsEntry("level", 3);
        // 定义的类型随主体属性发布，业务服务据此加载引用扩展属性的 Data Policy。
        assertThat(subject.attributeTypes()).containsEntry("level", "number");

        // 移动上级部门改变了成员所在部门的路径：版本递增，主体属性随之更新。
        before = version(MEMBER);
        call(ADMIN, HttpMethod.PUT, "/org/departments/" + sales + "/parent", new HashMap<>(Map.of()));
        assertThat(version(MEMBER)).isGreaterThan(before);
        assertThat(subject(MEMBER).primaryDepartmentPath()).isEqualTo(label(sales) + "." + label(east));

        // 删除扩展属性定义后，成员的该属性值一并移除。
        call(ADMIN, HttpMethod.DELETE, "/org/extended-attributes/" + data(level).get("id"), null);
        assertThat(subject(MEMBER).attributes()).isEmpty();

        // 清空分配后不再属于任何部门。
        assign(MEMBER, null, List.of(), List.of(), Map.of());
        assertThat(subject(MEMBER)).isEqualTo(SubjectAttributes.NONE);
    }

    @Test
    void departmentsInUseCannotBeDeletedAndAssignmentsMustHaveAPrimaryDepartment() throws Exception {
        String parent = createDepartment(ADMIN, "运营", null);
        String child = createDepartment(ADMIN, "客服", parent);
        String position = (String) data(call(ADMIN, HttpMethod.POST, "/org/positions", Map.of("name", "专员")))
                .get("id");

        var concurrentOnly = assign(MEMBER, null, List.of(child), List.of(), Map.of());
        assertThat(concurrentOnly.getBody().get("code")).isEqualTo("organization.invalidAssignment");
        assign(MEMBER, child, List.of(), List.of(position), Map.of());

        var withChildren = call(ADMIN, HttpMethod.DELETE, "/org/departments/" + parent, null);
        assertThat(withChildren.getBody().get("code")).isEqualTo("organization.departmentNotEmpty");
        var withMembers = call(ADMIN, HttpMethod.DELETE, "/org/departments/" + child, null);
        assertThat(withMembers.getBody().get("code")).isEqualTo("organization.departmentNotEmpty");
        var positionInUse = call(ADMIN, HttpMethod.DELETE, "/org/positions/" + position, null);
        assertThat(positionInUse.getBody().get("code")).isEqualTo("organization.positionInUse");

        assign(MEMBER, null, List.of(), List.of(), Map.of());
        assertThat(call(ADMIN, HttpMethod.DELETE, "/org/departments/" + child, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(ADMIN, HttpMethod.DELETE, "/org/departments/" + parent, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(ADMIN, HttpMethod.DELETE, "/org/positions/" + position, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(paths(ADMIN)).doesNotContainKeys(parent, child);
    }

    @Test
    void organizationDataIsIsolatedPerTenantAndManagedOnlyByTenantAdmins() throws Exception {
        String department = createDepartment(ADMIN, "仅租户 A 可见", null);

        // 其他租户的管理员看不到、改不了、也不能把自己的成员分配进来。
        assertThat(paths(ADMIN_B)).doesNotContainKey(department);
        var rename = call(ADMIN_B, HttpMethod.PUT, "/org/departments/" + department, Map.of("name", "越权"));
        assertThat(rename.getBody().get("code")).isEqualTo("organization.departmentNotFound");
        var foreignDepartment = assign(ADMIN_B, department, List.of(), List.of(), Map.of());
        assertThat(foreignDepartment.getBody().get("code")).isEqualTo("organization.departmentNotFound");
        // 也不能分配其他租户的成员。
        var foreignMember = call(
                ADMIN_B,
                HttpMethod.PUT,
                "/org/memberships/" + MEMBER + "/assignment",
                Map.of("departmentIds", List.of(), "positionIds", List.of(), "attributes", Map.of()));
        assertThat(foreignMember.getBody().get("code")).isEqualTo("organization.membershipNotFound");

        // 没有管理角色的成员不能管理组织。
        assertThat(call(MEMBER, HttpMethod.GET, "/org/departments", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);

        // 成员列表只含本租户成员。
        var members = call(ADMIN_B, HttpMethod.GET, "/org/memberships?page=1&size=100", null);
        assertThat(list(members).stream()
                        .map(member -> (String) ((Map<?, ?>) member).get("membershipId"))
                        .toList())
                .containsExactly(ADMIN_B.toString());
    }

    private ResponseEntity<Map> assign(
            UUID membership,
            String primary,
            List<String> departments,
            List<String> positions,
            Map<String, ?> attributes)
            throws Exception {
        var body = new HashMap<String, Object>();
        body.put("primaryDepartmentId", primary);
        body.put("departmentIds", departments);
        body.put("positionIds", positions);
        body.put("attributes", attributes);
        UUID admin = membership.equals(ADMIN_B) ? ADMIN_B : ADMIN;
        return call(admin, HttpMethod.PUT, "/org/memberships/" + membership + "/assignment", body);
    }

    private long version(UUID membership) {
        String value = redisTemplate.opsForValue().get(MembershipStateKeys.version(TENANT, membership));
        return value == null ? 1 : Long.parseLong(value);
    }

    // 按业务服务的方式读取：SDK 的明细模型直接反序列化 iam 发布的 JSON。
    private SubjectAttributes subject(UUID membership) {
        String value =
                redisTemplate.opsForValue().get(MembershipStateKeys.grants(TENANT, membership, version(membership)));
        return JsonMapper.builder()
                .build()
                .readValue(value, MembershipGrants.class)
                .subject();
    }

    private String createDepartment(UUID membership, String name, String parentId) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("parentId", parentId);
        var response = call(membership, HttpMethod.POST, "/org/departments", body);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return (String) data(response).get("id");
    }

    private Map<String, String> paths(UUID membership) throws Exception {
        var paths = new HashMap<String, String>();
        for (Object item : list(call(membership, HttpMethod.GET, "/org/departments", null))) {
            var department = (Map<?, ?>) item;
            paths.put((String) department.get("id"), (String) department.get("path"));
        }
        return paths;
    }

    private static String label(String id) {
        return id.replace("-", "");
    }

    private static Map<?, ?> data(ResponseEntity<Map> response) {
        return (Map<?, ?>) response.getBody().get("data");
    }

    private static List<?> list(ResponseEntity<Map> response) {
        return (List<?>) response.getBody().get("data");
    }

    private ResponseEntity<Map> call(UUID membership, HttpMethod method, String uri, Object body) throws Exception {
        String token = token(membership);
        var request = client().method(method).uri(uri).headers(headers -> headers.setBearerAuth(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    // 以 iam 自己的签名密钥签发 Access Token，省去完整的登录与授权码流程；登录链路由 TenantLoginTest 覆盖。
    private static String token(UUID membership) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(KEY_FILE)));
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8080/iam")
                .subject(IDENTITIES.get(membership).toString())
                .audience("tenant-app")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", (membership.equals(ADMIN_B) ? TENANT_B : TENANT).toString())
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
