package io.saasforge.sdk.abac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.core.ResourceManifest.DataAttribute;
import io.saasforge.sdk.core.ResourceManifest.DataResource;
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
}
