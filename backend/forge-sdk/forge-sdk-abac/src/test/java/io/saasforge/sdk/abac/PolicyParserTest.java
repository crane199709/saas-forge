package io.saasforge.sdk.abac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.abac.Expression.And;
import io.saasforge.sdk.abac.Expression.Comparison;
import io.saasforge.sdk.abac.Operand.AttributeRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class PolicyParserTest {
    @Test
    void parsesNestedTreeWithReference() {
        Expression expression = TestCatalog.parse("""
                {"and": [
                  {"attr": "resource.departmentPath", "op": "under", "value": {"ref": "subject.departmentPaths"}},
                  {"not": {"attr": "resource.archived", "op": "eq", "value": true}}
                ]}""");

        assertThat(expression).isInstanceOf(And.class);
        var under = (Comparison) ((And) expression).children().get(0);
        assertThat(under.operator()).isEqualTo(Operator.UNDER);
        assertThat(((AttributeRef) under.operand()).attribute().name()).isEqualTo("subject.departmentPaths");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            非 JSON                | {"attr":                                                                   | ''
            根节点不是对象          | []                                                                          | ''
            未知属性               | {"attr":"resource.secret","op":"eq","value":"x"}                           | /attr
            未知命名空间            | {"attr":"tenant.id","op":"eq","value":"x"}                                 | /attr
            未知操作符             | {"attr":"resource.title","op":"like","value":"x"}                          | /op
            嵌套中的未知操作符       | {"or":[{"and":[{"attr":"resource.title","op":"regex","value":"x"}]}]}      | /or/0/and/0/op
            未知字段               | {"attr":"resource.title","op":"eq","value":"x","extra":1}                  | /extra
            逻辑节点混入其他字段     | {"and":[],"attr":"resource.title"}                                         | ''
            and 不是数组           | {"and":{}}                                                                 | /and
            字符串属性给数字        | {"attr":"resource.title","op":"eq","value":1}                              | /value
            数值属性给字符串        | {"attr":"resource.amount","op":"gt","value":"100"}                         | /value
            布尔属性给字符串        | {"attr":"resource.archived","op":"eq","value":"true"}                      | /value
            非法 UUID              | {"attr":"resource.ownerId","op":"eq","value":"not-a-uuid"}                 | /value
            非法 ltree             | {"attr":"resource.departmentPath","op":"under","value":"root..sales"}      | /value
            in 给单值              | {"attr":"resource.title","op":"in","value":"alpha"}                        | /value
            eq 给数组              | {"attr":"resource.title","op":"eq","value":["alpha"]}                      | /value
            集合元素类型不符        | {"attr":"resource.title","op":"in","value":["alpha",1]}                    | /value/1
            集合含 null            | {"attr":"resource.title","op":"in","value":["alpha",null]}                 | /value/1
            null 字面量            | {"attr":"resource.title","op":"eq","value":null}                           | /value
            缺少 value             | {"attr":"resource.title","op":"eq"}                                        | /value
            is_null 带 value       | {"attr":"resource.title","op":"is_null","value":"x"}                       | /value
            字符串大小比较          | {"attr":"resource.title","op":"gt","value":"a"}                            | /attr
            单值属性 contains      | {"attr":"resource.title","op":"contains","value":"a"}                      | /attr
            集合属性 eq            | {"attr":"resource.tags","op":"eq","value":"red"}                           | /attr
            非 ltree 属性 under    | {"attr":"resource.title","op":"under","value":"root"}                      | /attr
            引用资源属性            | {"attr":"resource.title","op":"eq","value":{"ref":"resource.title"}}       | /value/ref
            引用未知属性            | {"attr":"resource.title","op":"eq","value":{"ref":"subject.nickname"}}     | /value/ref
            引用类型不符            | {"attr":"resource.title","op":"eq","value":{"ref":"subject.level"}}        | /value/ref
            eq 引用集合属性         | {"attr":"resource.title","op":"eq","value":{"ref":"subject.positions"}}    | /value/ref
            in 引用单值属性         | {"attr":"resource.title","op":"in","value":{"ref":"subject.position"}}     | /value/ref
            引用对象多余字段        | {"attr":"resource.title","op":"eq","value":{"ref":"subject.position","x":1}} | /value
            """)
    void rejectsInvalidPolicyAtLoadTime(String scenario, String json, String path) {
        assertThatThrownBy(() -> TestCatalog.parse(json))
                .isInstanceOf(PolicyValidationException.class)
                .extracting(e -> ((PolicyValidationException) e).path())
                .isEqualTo(path);
    }

    @ParameterizedTest
    @EnumSource(DataScope.class)
    void presetsRequireTheirAttributesInCatalog(DataScope scope) {
        assertThat(scope.expression(TestCatalog.CATALOG)).isNotNull();
        var empty = AttributeCatalog.of(List.of());
        if (scope == DataScope.ALL) {
            assertThat(scope.expression(empty)).isEqualTo(new And(List.of()));
        } else {
            assertThatThrownBy(() -> scope.expression(empty)).isInstanceOf(PolicyValidationException.class);
        }
    }

    @Test
    void compiledSqlNeverContainsUserValues() {
        String hostile = "x'); DROP TABLE abac_document; --";
        Expression expression = TestCatalog.parse("{\"or\":[{\"attr\":\"resource.title\",\"op\":\"eq\",\"value\":\""
                + hostile.replace("\"", "\\\"")
                + "\"},{\"attr\":\"resource.title\",\"op\":\"in\",\"value\":{\"ref\":\"subject.positions\"}}]}");

        SqlPredicate predicate = PolicySqlCompiler.compile(
                expression, Map.of("subject.positions", List.of("O'Brien")), TestCatalog.COLUMNS);

        assertThat(predicate.sql())
                .isEqualTo("(d.title = CAST(? AS text) OR d.title = ANY(CAST(? AS text[])))")
                .doesNotContain("DROP", "Brien");
        assertThat(predicate.parameters())
                .containsExactly(
                        new SqlParameter(AttributeType.STRING, false, hostile),
                        new SqlParameter(AttributeType.STRING, true, List.of("O'Brien")));
    }

    @Test
    void compilerRejectsUnmappedOrUnsafeColumns() {
        Expression expression = TestCatalog.parse("{\"attr\":\"resource.title\",\"op\":\"is_null\"}");

        assertThatThrownBy(() -> PolicySqlCompiler.compile(expression, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resource.title");
        assertThatThrownBy(() -> PolicySqlCompiler.compile(
                        expression, Map.of(), Map.of("resource.title", "title; DROP TABLE x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列名不合法");
    }

    @Test
    void unionOfNoPoliciesDeniesEverything() {
        Expression none = Expression.union(List.of());

        assertThat(PolicySqlCompiler.compile(none, Map.of(), TestCatalog.COLUMNS)
                        .sql())
                .isEqualTo("FALSE");
        assertThat(PolicyEvaluator.isSatisfied(none, Map.of(), Map.of())).isFalse();
    }
}
