package io.saasforge.sdk.abac;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 在真实 PostgreSQL 上执行编译后的 SQL，与内存求值逐行比对（ADR-0006 要求两个执行器结论一致）。 */
@Testcontainers
class PolicyConsistencyTest {
    private static final String A = "019a0110-0000-7000-8000-00000000000a";

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine").withInitScript("abac/documents.sql");

    private static Connection connection;
    private static List<Row> rows;

    /** 一行数据：主键与按资源属性全名组织的值。 */
    private record Row(int id, Map<String, Object> attributes) {}

    @BeforeAll
    static void loadRows() throws SQLException {
        connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        rows = new ArrayList<>();
        try (var statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT * FROM abac_document ORDER BY id")) {
            while (resultSet.next()) {
                var attributes = new HashMap<String, Object>();
                attributes.put("resource.ownerId", resultSet.getObject("owner_id", UUID.class));
                attributes.put("resource.departmentPath", resultSet.getString("department_path"));
                attributes.put("resource.title", resultSet.getString("title"));
                attributes.put("resource.amount", resultSet.getBigDecimal("amount"));
                attributes.put("resource.archived", resultSet.getObject("archived", Boolean.class));
                Array tags = resultSet.getArray("tags");
                attributes.put("resource.tags", tags == null ? null : Arrays.asList((Object[]) tags.getArray()));
                rows.add(new Row(resultSet.getInt("id"), attributes));
            }
        }
        assertThat(rows).hasSize(8);
    }

    @AfterAll
    static void close() throws SQLException {
        connection.close();
    }

    static Stream<Arguments> policiesAndSubjects() {
        Map<String, Map<String, Object>> subjects = new LinkedHashMap<>();
        subjects.put(
                "销售经理",
                Map.of(
                        "subject.membershipId",
                        UUID.fromString(A),
                        "subject.departmentPaths",
                        List.of("root.sales", "root.rd.lab"),
                        "subject.position",
                        "red",
                        "subject.positions",
                        List.of("manager", "auditor"),
                        "subject.level",
                        3,
                        "env.region",
                        "cn"));
        // 主体属性全部缺失：单值为 NULL、集合为空，覆盖未知值的传播
        subjects.put("空主体", Map.of());

        Map<String, Expression> policies = new LinkedHashMap<>();
        cases().forEach((name, json) -> policies.put(name, TestCatalog.parse(json)));
        for (DataScope scope : DataScope.values()) {
            policies.put("预设 " + scope, scope.expression(TestCatalog.CATALOG));
        }
        policies.put(
                "并集 本人 ∪ 本部门",
                Expression.union(List.of(
                        DataScope.SELF.expression(TestCatalog.CATALOG),
                        DataScope.DEPARTMENT.expression(TestCatalog.CATALOG))));
        policies.put("并集 空", Expression.union(List.of()));

        return policies.entrySet().stream()
                .flatMap(policy -> subjects.entrySet().stream()
                        .map(subject -> Arguments.of(
                                policy.getKey(), subject.getKey(), policy.getValue(), subject.getValue())));
    }

    private static Map<String, String> cases() {
        var cases = new LinkedHashMap<String, String>();
        cases.put("eq 文本", cmp("resource.title", "eq", "\"alpha\""));
        cases.put("eq UUID 字面量", cmp("resource.ownerId", "eq", "\"" + A + "\""));
        cases.put("eq 数值精度无关", cmp("resource.amount", "eq", "100"));
        cases.put("eq 布尔", cmp("resource.archived", "eq", "false"));
        cases.put("eq ltree", cmp("resource.departmentPath", "eq", "\"root.sales\""));
        cases.put("eq 引用单值", cmp("resource.ownerId", "eq", ref("subject.membershipId")));
        cases.put("ne 文本", cmp("resource.title", "ne", "\"alpha\""));
        cases.put("ne 布尔", cmp("resource.archived", "ne", "true"));
        cases.put("ne 数值", cmp("resource.amount", "ne", "100.0"));
        cases.put("in 文本", cmp("resource.title", "in", "[\"alpha\", \"beta\", \"O'Brien\"]"));
        cases.put(
                "in UUID", cmp("resource.ownerId", "in", "[\"" + A + "\", \"019a0110-0000-7000-8000-00000000000c\"]"));
        cases.put("in 空集合", cmp("resource.amount", "in", "[]"));
        cases.put("in 引用集合", cmp("resource.departmentPath", "in", ref("subject.departmentPaths")));
        cases.put("nin 文本", cmp("resource.title", "nin", "[\"alpha\"]"));
        cases.put("nin 空集合", cmp("resource.title", "nin", "[]"));
        cases.put("nin 引用集合", cmp("resource.departmentPath", "nin", ref("subject.departmentPaths")));
        cases.put("gt", cmp("resource.amount", "gt", "50.5"));
        cases.put("gte", cmp("resource.amount", "gte", "100"));
        cases.put("lt", cmp("resource.amount", "lt", "0"));
        cases.put("lte", cmp("resource.amount", "lte", "0"));
        cases.put("gt 引用数值", cmp("resource.amount", "gt", ref("subject.level")));
        cases.put("contains 命中", cmp("resource.tags", "contains", "\"red\""));
        cases.put("contains 遇到 NULL 元素", cmp("resource.tags", "contains", "\"blue\""));
        cases.put("contains 未命中", cmp("resource.tags", "contains", "\"missing\""));
        cases.put("contains 引用单值", cmp("resource.tags", "contains", ref("subject.position")));
        cases.put("is_null 单值", "{\"attr\": \"resource.ownerId\", \"op\": \"is_null\"}");
        cases.put("is_null 数组", "{\"attr\": \"resource.tags\", \"op\": \"is_null\"}");
        cases.put("under 单路径", cmp("resource.departmentPath", "under", "\"root.sales\""));
        cases.put("under 多路径", cmp("resource.departmentPath", "under", "[\"root.rd\", \"root.sales.east\"]"));
        cases.put("under 空集合", cmp("resource.departmentPath", "under", "[]"));
        cases.put("under 引用集合", cmp("resource.departmentPath", "under", ref("subject.departmentPaths")));
        cases.put("not eq 遇到 NULL", not(cmp("resource.title", "eq", "\"alpha\"")));
        cases.put("not contains", not(cmp("resource.tags", "contains", "\"red\"")));
        cases.put("not nin 空集合", not(cmp("resource.title", "nin", "[]")));
        cases.put("not is_null", not("{\"attr\": \"resource.archived\", \"op\": \"is_null\"}"));
        cases.put(
                "and 嵌套 or",
                "{\"and\": [" + cmp("resource.archived", "eq", "false") + ", {\"or\": ["
                        + cmp("resource.amount", "gte", "200") + ", " + cmp("resource.title", "eq", "\"alpha\"")
                        + "]}]}");
        cases.put(
                "主体属性条件 and 资源条件",
                "{\"and\": [" + cmp("subject.positions", "contains", "\"manager\"") + ", "
                        + cmp("resource.amount", "gt", "0") + "]}");
        cases.put(
                "环境属性 or 资源条件",
                "{\"or\": [" + cmp("env.region", "eq", "\"us\"") + ", " + cmp("resource.archived", "eq", "true")
                        + "]}");
        cases.put("not 主体属性条件", not(cmp("subject.position", "eq", "\"red\"")));
        cases.put(
                "not or 混合未知",
                not("{\"or\": [" + cmp("subject.level", "lt", "5") + ", " + cmp("resource.amount", "gt", "100")
                        + "]}"));
        return cases;
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("policiesAndSubjects")
    void sqlAndInMemoryAgreeRowByRow(
            String policyName, String subjectName, Expression policy, Map<String, Object> subject) throws SQLException {
        Set<Integer> expected = new TreeSet<>();
        for (Row row : rows) {
            if (PolicyEvaluator.isSatisfied(policy, subject, row.attributes())) {
                expected.add(row.id());
            }
        }

        assertThat(query(PolicySqlCompiler.compile(policy, subject, TestCatalog.COLUMNS)))
                .as("SQL 与内存求值结果应逐行一致")
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("presetExpectations")
    void presetsSelectExpectedRows(DataScope scope, Set<Integer> expected) throws SQLException {
        Map<String, Object> subject = Map.of(
                "subject.membershipId", UUID.fromString(A),
                "subject.departmentPaths", List.of("root.sales", "root.rd.lab"));

        assertThat(query(
                        PolicySqlCompiler.compile(scope.expression(TestCatalog.CATALOG), subject, TestCatalog.COLUMNS)))
                .isEqualTo(expected);
    }

    static Stream<Arguments> presetExpectations() {
        return Stream.of(
                Arguments.of(DataScope.SELF, Set.of(1, 3, 8)),
                Arguments.of(DataScope.DEPARTMENT, Set.of(1, 8)),
                // root.salesx 与 root.sales 前缀相同但不在其子树下
                Arguments.of(DataScope.DEPARTMENT_AND_BELOW, Set.of(1, 2, 3, 8)),
                Arguments.of(DataScope.ALL, Set.of(1, 2, 3, 4, 5, 6, 7, 8)));
    }

    private static Set<Integer> query(SqlPredicate predicate) throws SQLException {
        // 本测试验证 SDK 的 SQL 编译器本身，属于编码规范中 ABAC 编译器的豁免范围
        String sql = "SELECT d.id FROM abac_document d WHERE " + predicate.sql();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            List<SqlParameter> parameters = predicate.parameters();
            for (int i = 0; i < parameters.size(); i++) {
                bind(statement, i + 1, parameters.get(i));
            }
            Set<Integer> ids = new TreeSet<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(resultSet.getInt(1));
                }
            }
            return ids;
        }
    }

    private static void bind(PreparedStatement statement, int index, SqlParameter parameter) throws SQLException {
        if (parameter.array()) {
            // ltree 数组以 text[] 传入，再由 SQL 中的 CAST 转为 ltree[]
            String elementType = parameter.type() == AttributeType.LTREE
                    ? "text"
                    : parameter.type().sqlType();
            statement.setArray(index, connection.createArrayOf(elementType, ((List<?>) parameter.value()).toArray()));
        } else if (parameter.value() == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setObject(index, parameter.value());
        }
    }

    private static String cmp(String attr, String op, String value) {
        return "{\"attr\": \"" + attr + "\", \"op\": \"" + op + "\", \"value\": " + value + "}";
    }

    private static String ref(String attr) {
        return "{\"ref\": \"" + attr + "\"}";
    }

    private static String not(String child) {
        return "{\"not\": " + child + "}";
    }
}
