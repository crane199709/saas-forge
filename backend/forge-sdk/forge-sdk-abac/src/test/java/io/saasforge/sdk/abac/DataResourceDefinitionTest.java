package io.saasforge.sdk.abac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.core.ResourceManifest.DataAttribute;
import io.saasforge.sdk.core.ResourceManifest.DataResource;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DataResourceDefinitionTest {
    static final DataResource ORDER = new DataResource(
            "order",
            "订单",
            "Orders",
            List.of(
                    new DataAttribute("ownerId", "归属成员", "uuid", "owner_id"),
                    new DataAttribute("departmentPath", "归属部门", "ltree", "department_path")));

    @Test
    void presetsCompileAgainstTheDeclaredColumnsQualifiedByTheTableAlias() {
        var definition = DataResourceDefinition.of(ORDER);
        UUID me = UUID.randomUUID();

        SqlPredicate predicate = PolicySqlCompiler.compile(
                definition.parse(DataScope.SELF.template()),
                Map.of("subject.membershipId", me),
                definition.columns("o"));

        assertThat(definition.table()).isEqualTo("orders");
        assertThat(predicate.sql()).isEqualTo("o.owner_id = CAST(? AS uuid)");
        assertThat(predicate.parameters()).containsExactly(new SqlParameter(AttributeType.UUID, false, me));
        assertThat(definition.availableScopes()).containsExactly(DataScope.values());
        assertThat(definition.resourceValues(Map.of("ownerId", me))).isEqualTo(Map.of("resource.ownerId", me));
    }

    @Test
    void presetsReferringToUndeclaredAttributesAreUnavailable() {
        var definition = DataResourceDefinition.of(new DataResource(
                "note", "便签", "notes", List.of(new DataAttribute("ownerId", "归属成员", "uuid", "owner_id"))));

        assertThat(definition.availableScopes()).containsExactly(DataScope.SELF, DataScope.ALL);
        assertThatThrownBy(() -> definition.parse(DataScope.DEPARTMENT.template()))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("resource.departmentPath");
        assertThatThrownBy(() -> definition.resourceValues(Map.of("title", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customConditionsReferToExtendedAttributesByTheirTenantTypesAndToTheEnvironment() {
        var definition = DataResourceDefinition.of(new DataResource(
                "order", "订单", "orders", List.of(new DataAttribute("amount", "金额", "number", "amount"))));
        String json = """
                {"and": [
                  {"attr": "resource.amount", "op": "gt", "value": {"ref": "subject.attributes.limit"}},
                  {"attr": "subject.attributes.vip", "op": "eq", "value": true},
                  {"attr": "env.now", "op": "gte", "value": 1700000000}
                ]}""";
        Map<String, String> types = Map.of("limit", "number", "vip", "boolean", "region", "string");
        Expression expression = definition.parse(json, types);

        var context =
                new HashMap<String, Object>(Map.of("subject.attributes.limit", 1000, "subject.attributes.vip", true));
        context.putAll(DataResourceDefinition.environment(Instant.ofEpochSecond(1_800_000_000L)));
        assertThat(context).containsEntry("env.now", 1_800_000_000L);
        assertThat(PolicyEvaluator.isSatisfied(expression, context, Map.of("resource.amount", 1500)))
                .isTrue();
        assertThat(PolicyEvaluator.isSatisfied(expression, context, Map.of("resource.amount", 500)))
                .isFalse();
        assertThat(PolicySqlCompiler.compile(expression, context, definition.columns(null))
                        .sql())
                .isEqualTo("(amount > CAST(? AS numeric) AND TRUE AND TRUE)");

        // 未定义的扩展属性与类型不符的比较都在加载时报错；没有给出租户定义时扩展属性一律未知。
        assertThatThrownBy(() -> definition.parse(json))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("subject.attributes.limit");
        assertThatThrownBy(() -> definition.parse(json, Map.of("limit", "string", "vip", "boolean")))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("/and/0/value/ref");
        assertThat(DataResourceDefinition.extendedAttributes(types))
                .contains(AttributeDefinition.single("subject.attributes.region", AttributeType.STRING));
    }
}
