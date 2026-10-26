package io.saasforge.sdk.abac;

import io.saasforge.sdk.abac.Expression.And;
import io.saasforge.sdk.abac.Expression.Comparison;
import io.saasforge.sdk.abac.Expression.Not;
import io.saasforge.sdk.abac.Expression.Or;
import io.saasforge.sdk.abac.PolicyEvaluator.Truth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把 Data Policy 编译为 PostgreSQL 参数化谓词，用于列表查询、批量更新与删除的过滤。
 *
 * <p>主体与环境属性在编译时即已确定：引用它们的值作为参数绑定；左侧不是资源属性的比较直接用 {@link PolicyEvaluator} 求出常量，
 * 因此两边结论天然一致。所有值都以 {@code ?} 占位符输出，SQL 文本中只出现调用方提供并经过校验的列名。
 */
public final class PolicySqlCompiler {
    // 列名由 Business Service 声明，仍要校验，防止映射配置被当作 SQL 注入入口
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private PolicySqlCompiler() {}

    /**
     * 编译策略。
     *
     * @param expression 已校验的策略
     * @param context 主体与环境属性值，键为全名
     * @param columns 资源属性全名到列引用的映射，如 {@code resource.ownerId → o.owner_id}；策略引用的资源属性都必须有映射
     * @return 参数化谓词
     * @throws IllegalArgumentException 缺少列映射、列名不合法，或上下文属性值与类型不符
     */
    public static SqlPredicate compile(Expression expression, Map<String, ?> context, Map<String, String> columns) {
        var run = new Run(context, columns);
        String sql = run.expression(expression);
        return new SqlPredicate(sql, run.parameters);
    }

    private static final class Run {
        private final Map<String, ?> context;
        private final Map<String, String> columns;
        private final List<SqlParameter> parameters = new ArrayList<>();

        Run(Map<String, ?> context, Map<String, String> columns) {
            this.context = context;
            this.columns = columns;
        }

        String expression(Expression expression) {
            if (expression instanceof And and) {
                return join(and.children(), " AND ", "TRUE");
            }
            if (expression instanceof Or or) {
                return join(or.children(), " OR ", "FALSE");
            }
            if (expression instanceof Not not) {
                return "(NOT " + expression(not.child()) + ")";
            }
            return comparison((Comparison) expression);
        }

        private String join(List<Expression> children, String separator, String empty) {
            if (children.isEmpty()) {
                return empty;
            }
            var parts = new ArrayList<String>(children.size());
            for (Expression child : children) {
                parts.add(expression(child));
            }
            return "(" + String.join(separator, parts) + ")";
        }

        private String comparison(Comparison comparison) {
            AttributeDefinition attribute = comparison.attribute();
            if (!attribute.isResource()) {
                return constant(PolicyEvaluator.truth(comparison, context, Map.of()));
            }
            String column = column(attribute);
            AttributeType type = attribute.type();
            Object value = PolicyEvaluator.operandValue(comparison.operand(), context);
            return switch (comparison.operator()) {
                case EQ -> column + " = " + scalar(type, value);
                case NE -> column + " <> " + scalar(type, value);
                case GT -> column + " > " + scalar(type, value);
                case GTE -> column + " >= " + scalar(type, value);
                case LT -> column + " < " + scalar(type, value);
                case LTE -> column + " <= " + scalar(type, value);
                case IN -> column + " = ANY(" + array(type, value) + ")";
                case NIN -> column + " <> ALL(" + array(type, value) + ")";
                case CONTAINS -> scalar(type, value) + " = ANY(" + column + ")";
                case IS_NULL -> column + " IS NULL";
                // ltree <@ ltree[]：数组中任一路径是其祖先（含自身）即为真
                case UNDER -> column + " <@ " + (value instanceof List ? array(type, value) : scalar(type, value));
            };
        }

        private String column(AttributeDefinition attribute) {
            String column = columns.get(attribute.name());
            if (column == null) {
                throw new IllegalArgumentException("资源属性缺少列映射：" + attribute.name());
            }
            if (!COLUMN.matcher(column).matches()) {
                throw new IllegalArgumentException("列名不合法：" + column);
            }
            return column;
        }

        private String scalar(AttributeType type, Object value) {
            parameters.add(new SqlParameter(type, false, value));
            return "CAST(? AS " + type.sqlType() + ")";
        }

        private String array(AttributeType type, Object value) {
            parameters.add(new SqlParameter(type, true, value));
            return "CAST(? AS " + type.sqlType() + "[])";
        }

        private static String constant(Truth truth) {
            return switch (truth) {
                case TRUE -> "TRUE";
                case FALSE -> "FALSE";
                case UNKNOWN -> "CAST(NULL AS boolean)";
            };
        }
    }
}
