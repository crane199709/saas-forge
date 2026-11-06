#set( $dollar = '$' )
#set( $hash = '#' )
package ${package};

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.abac.data.DataPermission;
import io.saasforge.sdk.core.SubjectAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * 订单按当前成员的 Data Scope 过滤（工单 18）：不同部门、不同角色组合的成员看到的订单集合，越权更新与删除，
 * 以及单对象校验与 SQL 过滤结论一致；编辑器保存的自定义条件同样生效（工单 19）。
 *
 * <p>部门树：{@code a}、其下级 {@code a.b} 与另一个顶级部门 {@code c}。每个成员先以「全部」范围、按自己的主部门创建一张订单，
 * 之后以新版本的明细（等同于 iam 调整角色或组织分配后换发 Token）按不同范围查询。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataPermissionTest {
    private static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-0000000000f0");
    private static final UUID ALICE = UUID.fromString("019a0110-0000-7000-8000-0000000000f1");
    private static final UUID BOB = UUID.fromString("019a0110-0000-7000-8000-0000000000f2");
    private static final UUID CAROL = UUID.fromString("019a0110-0000-7000-8000-0000000000f3");
    private static final UUID DAVE = UUID.fromString("019a0110-0000-7000-8000-0000000000f4");
    private static final UUID ERIN = UUID.fromString("019a0110-0000-7000-8000-0000000000f5");
    private static final Map<UUID, String> NAMES =
            Map.of(ALICE, "alice", BOB, "bob", CAROL, "carol", DAVE, "dave", ERIN, "erin");
    // 金额各不相同，供自定义条件按金额过滤
    private static final Map<UUID, Integer> AMOUNTS = Map.of(ALICE, 1, BOB, 2000, CAROL, 500, DAVE, 3000, ERIN, 1);
    // 编辑器保存的「金额大于 1000 且属于本部门及下级」
    private static final String LARGE_IN_MY_DEPARTMENTS = """
            {"and": [
              {"attr": "resource.amount", "op": "gt", "value": 1000},
              {"attr": "resource.departmentPath", "op": "under", "value": {"ref": "subject.departmentPaths"}}
            ]}""";
    private static final String VIP_SEES_ALL = """
            {"attr": "subject.attributes.vip", "op": "eq", "value": true}""";
    private static final Map<UUID, SubjectAttributes> DEPARTMENTS = Map.of(
            ALICE, inDepartments("a"),
            BOB, inDepartments("a.b"),
            CAROL, inDepartments("a.b"),
            DAVE, inDepartments("c"),
            ERIN, SubjectAttributes.NONE);

    static final SharedContainers.Postgres postgres = SharedContainers.postgres();

    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        TestTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    DataPermission dataPermission;

    // 每次换一个版本发布明细：SDK 按 membership_id + version 缓存明细，同一版本内的覆盖不会立即被读到。
    private static long nextVersion = 10;
    // 容器与应用上下文在各测试间共用，订单只创建一次；不能用 PER_CLASS 的 @BeforeAll，那会让上下文先于容器启动。
    private static boolean seeded;

    @BeforeEach
    void eachMemberCreatesAnOrderInTheirPrimaryDepartment() throws Exception {
        if (seeded) return;
        seeded = true;
        for (UUID member : NAMES.keySet()) {
            String token = publish(member, DEPARTMENTS.get(member), List.of(DataScope.ALL));
            assertThat(status(
                            token,
                            HttpMethod.POST,
                            "/orders",
                            Map.of("title", NAMES.get(member), "amount", AMOUNTS.get(member))))
                    .isEqualTo(201);
        }
    }

    @Test
    void membersSeeTheOrdersWithinTheUnionOfTheirRolesDataScopes() throws Exception {
        assertVisible(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.SELF), "alice");
        // 「本部门」不含下级部门，「本部门及下级」包含。
        assertVisible(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.DEPARTMENT), "alice");
        assertVisible(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.DEPARTMENT_AND_BELOW), "alice", "bob", "carol");
        assertVisible(CAROL, DEPARTMENTS.get(CAROL), List.of(DataScope.DEPARTMENT), "bob", "carol");
        assertVisible(CAROL, DEPARTMENTS.get(CAROL), List.of(DataScope.ALL), "alice", "bob", "carol", "dave", "erin");
        // 主部门与兼任部门都计入「本部门」。
        assertVisible(DAVE, inDepartments("c", "a"), List.of(DataScope.DEPARTMENT), "alice", "dave");
        // 两个角色的范围取并集：Erin 自己创建时没有部门，之后被分配到 c，「本人」与「本部门」各得一张。
        assertVisible(ERIN, inDepartments("c"), List.of(DataScope.SELF, DataScope.DEPARTMENT), "dave", "erin");
        // 没有部门的成员按部门的范围看不到任何订单，按「本人」只看到自己的。
        assertVisible(ERIN, SubjectAttributes.NONE, List.of(DataScope.DEPARTMENT_AND_BELOW));
        // 有查看权限码但没有任何 Data Policy：默认拒绝。
        assertVisible(BOB, DEPARTMENTS.get(BOB), List.of());
    }

    @Test
    void updatesAndDeletesOutsideTheDataScopeAreRejectedAndLeaveTheOrderUntouched() throws Exception {
        Map<String, UUID> ids = orderIds();
        String alice = publish(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.SELF));

        // 范围外的订单与不存在的订单一样 404，不泄露其存在。
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
            Object body = method == HttpMethod.PUT ? Map.of("title", "越权", "amount", 99) : null;
            Map<String, Object> rejected = exchange(alice, method, "/orders/" + ids.get("bob"), body);
            assertThat(rejected.get("code")).isEqualTo("order.notFound");
            assertThat(rejected.get("httpStatus")).isEqualTo(404);
        }
        Map<?, ?> untouched = order(ids.get("bob"));
        assertThat(untouched.get("title")).isEqualTo("bob");
        assertThat(((Number) untouched.get("amount")).intValue()).isEqualTo(2000);

        // 范围内照常修改。
        assertThat(status(alice, HttpMethod.PUT, "/orders/" + ids.get("alice"), Map.of("title", "alice", "amount", 2)))
                .isEqualTo(200);
        assertThat(((Number) order(ids.get("alice")).get("amount")).intValue()).isEqualTo(2);
    }

    @Test
    void creatingAnOrderOutsideTheOwnDataScopeIsForbidden() throws Exception {
        // 新订单归属创建者的主部门；没有部门的成员按部门范围创建的订单自己也看不到，单对象校验直接拒绝。
        String byDepartment = publish(ERIN, SubjectAttributes.NONE, List.of(DataScope.DEPARTMENT));
        Map<String, Object> denied =
                exchange(byDepartment, HttpMethod.POST, "/orders", Map.of("title", "x", "amount", 1));
        assertThat(denied.get("code")).isEqualTo("auth.forbidden");
        assertThat(denied.get("httpStatus")).isEqualTo(403);

        String withoutPolicy = publish(ERIN, SubjectAttributes.NONE, List.of());
        assertThat(status(withoutPolicy, HttpMethod.POST, "/orders", Map.of("title", "x", "amount", 1)))
                .isEqualTo(403);
    }

    @Test
    void singleObjectCheckAgreesWithTheSqlFilterForEveryOrder() throws Exception {
        List<Map<?, ?>> all = list(publish(CAROL, DEPARTMENTS.get(CAROL), List.of(DataScope.ALL)));
        record Case(UUID member, SubjectAttributes subject, List<DataScope> scopes) {}
        for (Case c : List.of(
                new Case(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.SELF)),
                new Case(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.DEPARTMENT)),
                new Case(ALICE, DEPARTMENTS.get(ALICE), List.of(DataScope.DEPARTMENT_AND_BELOW)),
                new Case(DAVE, inDepartments("c", "a.b"), List.of(DataScope.SELF, DataScope.DEPARTMENT)),
                new Case(ERIN, SubjectAttributes.NONE, List.of(DataScope.DEPARTMENT_AND_BELOW)),
                new Case(BOB, DEPARTMENTS.get(BOB), List.of()))) {
            long version = nextVersion;
            String token = publish(c.member(), c.subject(), c.scopes());
            Set<Object> bySql =
                    list(token).stream().map(order -> order.get("id")).collect(Collectors.toSet());
            Set<Object> inMemory = new HashSet<>();
            asMember(c.member(), version, () -> {
                for (Map<?, ?> order : all) {
                    var attributes = new HashMap<String, Object>();
                    attributes.put("ownerId", UUID.fromString((String) order.get("ownerId")));
                    attributes.put("departmentPath", order.get("departmentPath"));
                    if (dataPermission.permits("order", attributes)) inMemory.add(order.get("id"));
                }
            });
            assertThat(inMemory).as("%s %s", NAMES.get(c.member()), c.scopes()).isEqualTo(bySql);
        }
    }

    @Test
    void customConditionsSavedByTheEditorFilterTheListAndAgreeWithTheSingleObjectCheck() throws Exception {
        // 「金额大于 1000 且属于本部门及下级」：Alice 在 a，下级 a.b 中只有 Bob 的订单超过 1000；Dave 的大额订单在 c。
        assertVisiblePolicies(ALICE, DEPARTMENTS.get(ALICE), List.of(LARGE_IN_MY_DEPARTMENTS), "bob");
        assertVisiblePolicies(DAVE, DEPARTMENTS.get(DAVE), List.of(LARGE_IN_MY_DEPARTMENTS), "dave");
        assertVisiblePolicies(ERIN, SubjectAttributes.NONE, List.of(LARGE_IN_MY_DEPARTMENTS));

        // 引用扩展属性的条件按发布的属性类型加载：VIP 看到全部，非 VIP 只剩另一个角色的条件。
        SubjectAttributes vip = withAttributes(DEPARTMENTS.get(ALICE), Map.of("vip", true));
        SubjectAttributes regular = withAttributes(DEPARTMENTS.get(ALICE), Map.of("vip", false));
        assertVisiblePolicies(
                ALICE, vip, List.of(LARGE_IN_MY_DEPARTMENTS, VIP_SEES_ALL), "alice", "bob", "carol", "dave", "erin");
        assertVisiblePolicies(ALICE, regular, List.of(LARGE_IN_MY_DEPARTMENTS, VIP_SEES_ALL), "bob");
        // 扩展属性定义已删除（明细中没有其类型）：该条策略按拒绝处理，不放行也不报错。
        var untyped = new SubjectAttributes(Set.of("a"), "a", Set.of(), Map.of("vip", true), Map.of());
        assertVisiblePolicies(ALICE, untyped, List.of(LARGE_IN_MY_DEPARTMENTS, VIP_SEES_ALL), "bob");

        List<Map<?, ?>> all = list(publish(CAROL, DEPARTMENTS.get(CAROL), List.of(DataScope.ALL)));
        for (SubjectAttributes subject : List.of(DEPARTMENTS.get(ALICE), vip, regular, inDepartments("c", "a.b"))) {
            long version = nextVersion;
            String token = publishPolicies(ALICE, subject, List.of(LARGE_IN_MY_DEPARTMENTS, VIP_SEES_ALL));
            Set<Object> bySql =
                    list(token).stream().map(order -> order.get("id")).collect(Collectors.toSet());
            Set<Object> inMemory = new HashSet<>();
            asMember(ALICE, version, () -> {
                for (Map<?, ?> order : all) {
                    var attributes = new HashMap<String, Object>();
                    attributes.put("ownerId", UUID.fromString((String) order.get("ownerId")));
                    attributes.put("departmentPath", order.get("departmentPath"));
                    attributes.put("amount", order.get("amount"));
                    if (dataPermission.permits("order", attributes)) inMemory.add(order.get("id"));
                }
            });
            assertThat(inMemory).as("%s", subject).isEqualTo(bySql);
        }
    }

    private void assertVisible(UUID member, SubjectAttributes subject, List<DataScope> scopes, String... titles)
            throws Exception {
        assertVisibleWith(publish(member, subject, scopes), NAMES.get(member) + " " + scopes, titles);
    }

    private void assertVisiblePolicies(UUID member, SubjectAttributes subject, List<String> policies, String... titles)
            throws Exception {
        assertVisibleWith(publishPolicies(member, subject, policies), NAMES.get(member) + " " + subject, titles);
    }

    private void assertVisibleWith(String token, String description, String... titles) throws Exception {
        var envelope = exchange(token, HttpMethod.GET, "/orders?size=100", null);
        List<Object> visible = ((List<?>) envelope.get("data"))
                .stream().<Object>map(order -> ((Map<?, ?>) order).get("title")).toList();
        assertThat(visible).as(description).containsExactlyInAnyOrder((Object[]) titles);
        // 总数与列表经同一过滤，分页信息不泄露范围外的数据量。
        assertThat(((Map<?, ?>) ((Map<?, ?>) envelope.get("meta")).get("page")).get("totalElements"))
                .isEqualTo(titles.length);
    }

    private Map<String, UUID> orderIds() throws Exception {
        return list(publish(CAROL, DEPARTMENTS.get(CAROL), List.of(DataScope.ALL))).stream()
                .filter(order -> NAMES.containsValue(order.get("title")))
                .collect(Collectors.toMap(
                        order -> (String) order.get("title"), order -> UUID.fromString((String) order.get("id"))));
    }

    private Map<?, ?> order(UUID id) throws Exception {
        String reader = publish(CAROL, DEPARTMENTS.get(CAROL), List.of(DataScope.ALL));
        return (Map<?, ?>)
                exchange(reader, HttpMethod.GET, "/orders/" + id, null).get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<?, ?>> list(String token) throws Exception {
        return (List<Map<?, ?>>)
                exchange(token, HttpMethod.GET, "/orders?size=100", null).get("data");
    }

    /** 以新版本发布成员明细（全部订单权限码与给定的数据范围），返回该版本的 Token。 */
    private String publish(UUID member, SubjectAttributes subject, List<DataScope> scopes) throws Exception {
        long version = nextVersion++;
        TestGrants.publish(redisTemplate, TENANT, member, version, TestGrants.ALL_ORDER_PERMISSIONS, subject, scopes);
        return TestTokens.forMembership(TENANT, member, version);
    }

    /** 以新版本发布成员明细（全部订单权限码与给定的订单策略 JSON），返回该版本的 Token。 */
    private String publishPolicies(UUID member, SubjectAttributes subject, List<String> policies) throws Exception {
        long version = nextVersion++;
        TestGrants.publishPolicies(
                redisTemplate, TENANT, member, version, TestGrants.ALL_ORDER_PERMISSIONS, subject, policies);
        return TestTokens.forMembership(TENANT, member, version);
    }

    /** 模拟已认证的成员请求线程，供直接调用单对象校验。 */
    private static void asMember(UUID member, long version, Runnable task) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(TestTokens.IDENTITY_ID.toString())
                .claim("tenant_id", TENANT.toString())
                .claim("membership_id", member.toString())
                .claim("ver", version)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        try {
            task.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static SubjectAttributes inDepartments(String primary, String... others) {
        var paths = new HashSet<>(List.of(others));
        paths.add(primary);
        return new SubjectAttributes(paths, primary, Set.of(), Map.of(), Map.of());
    }

    private static SubjectAttributes withAttributes(SubjectAttributes subject, Map<String, Object> attributes) {
        return new SubjectAttributes(
                subject.departmentPaths(),
                subject.primaryDepartmentPath(),
                subject.positionIds(),
                attributes,
                Map.of("vip", "boolean"));
    }

    private int status(String token, HttpMethod method, String uri, Object body) throws Exception {
        return (Integer) exchange(token, method, uri, body).get("httpStatus");
    }

    private Map<String, Object> exchange(String token, HttpMethod method, String uri, Object body) {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((req, response) -> {
            Map<String, Object> envelope = response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(envelope.get("httpStatus"))
                    .isEqualTo(response.getStatusCode().value());
            return envelope;
        });
    }
}
