package io.saasforge.sdk.abac;

import io.saasforge.sdk.abac.Expression.And;
import io.saasforge.sdk.abac.Expression.Comparison;
import io.saasforge.sdk.abac.Expression.Not;
import io.saasforge.sdk.abac.Expression.Or;
import io.saasforge.sdk.abac.Operand.AttributeRef;
import io.saasforge.sdk.abac.Operand.Literal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 在内存中对单个资源对象求值 Data Policy，用于单条操作校验。
 *
 * <p>为与 {@link PolicySqlCompiler} 生成的 SQL 结论一致，求值按 SQL 三值逻辑进行：属性为 {@code null} 时比较结果为「未知」，
 * {@code not} 未知仍是未知，最终只有结果为真才视为满足，与 {@code WHERE} 过滤掉 {@code NULL} 行的行为相同。
 */
public final class PolicyEvaluator {
    private PolicyEvaluator() {}

    /**
     * 判断资源对象是否满足策略。
     *
     * @param expression 已校验的策略
     * @param context 主体与环境属性值，键为全名，如 {@code subject.membershipId}
     * @param resource 资源属性值，键为全名，如 {@code resource.ownerId}
     * @return 策略结果为真时返回 {@code true}；结果为假或未知均返回 {@code false}
     * @throws IllegalArgumentException 属性值与声明的类型不符
     */
    public static boolean isSatisfied(Expression expression, Map<String, ?> context, Map<String, ?> resource) {
        return truth(expression, context, resource) == Truth.TRUE;
    }

    static Truth truth(Expression expression, Map<String, ?> context, Map<String, ?> resource) {
        if (expression instanceof And and) {
            Truth result = Truth.TRUE;
            for (Expression child : and.children()) {
                result = result.and(truth(child, context, resource));
            }
            return result;
        }
        if (expression instanceof Or or) {
            Truth result = Truth.FALSE;
            for (Expression child : or.children()) {
                result = result.or(truth(child, context, resource));
            }
            return result;
        }
        if (expression instanceof Not not) {
            return truth(not.child(), context, resource).not();
        }
        var comparison = (Comparison) expression;
        AttributeDefinition attribute = comparison.attribute();
        Object left = attribute.isResource()
                ? AttributeValues.resource(attribute, resource)
                : AttributeValues.context(attribute, context);
        return compare(comparison, left, operandValue(comparison.operand(), context));
    }

    /** 主体属性引用与字面量都在这里取值；编译器生成 SQL 参数时也走这里，保证两边取到同一个值。 */
    static Object operandValue(Operand operand, Map<String, ?> context) {
        if (operand == null) {
            return null;
        }
        if (operand instanceof Literal literal) {
            return literal.value();
        }
        return AttributeValues.context(((AttributeRef) operand).attribute(), context);
    }

    // 各分支逐一对应 PostgreSQL 中相应运算符遇到 NULL 与空数组时的结果
    private static Truth compare(Comparison comparison, Object left, Object right) {
        AttributeType type = comparison.attribute().type();
        switch (comparison.operator()) {
            case IS_NULL:
                return Truth.of(left == null);
            case EQ:
            case NE:
            case GT:
            case GTE:
            case LT:
            case LTE:
                if (left == null || right == null) {
                    return Truth.UNKNOWN;
                }
                return Truth.of(ordered(comparison.operator(), type, left, right));
            case IN: {
                // x = ANY('{}') 为假，即便 x 为 NULL
                List<?> candidates = (List<?>) right;
                if (candidates.isEmpty()) {
                    return Truth.FALSE;
                }
                return left == null ? Truth.UNKNOWN : Truth.of(anySame(type, left, candidates));
            }
            case NIN: {
                // x <> ALL('{}') 为真，即便 x 为 NULL
                List<?> candidates = (List<?>) right;
                if (candidates.isEmpty()) {
                    return Truth.TRUE;
                }
                return left == null ? Truth.UNKNOWN : Truth.of(!anySame(type, left, candidates));
            }
            case CONTAINS: {
                // v = ANY(col)：列为 NULL 时未知；列为空数组时为假；未命中但数组含 NULL 元素时未知
                if (left == null) {
                    return Truth.UNKNOWN;
                }
                List<?> elements = (List<?>) left;
                if (elements.isEmpty()) {
                    return Truth.FALSE;
                }
                if (right == null) {
                    return Truth.UNKNOWN;
                }
                boolean hasNull = false;
                for (Object element : elements) {
                    if (element == null) {
                        hasNull = true;
                    } else if (AttributeValues.same(type, element, right)) {
                        return Truth.TRUE;
                    }
                }
                return hasNull ? Truth.UNKNOWN : Truth.FALSE;
            }
            case UNDER: {
                if (left == null) {
                    return Truth.UNKNOWN;
                }
                if (right instanceof List<?> ancestors) {
                    for (Object ancestor : ancestors) {
                        if (isUnder((String) left, (String) ancestor)) {
                            return Truth.TRUE;
                        }
                    }
                    return Truth.FALSE;
                }
                return right == null ? Truth.UNKNOWN : Truth.of(isUnder((String) left, (String) right));
            }
            default:
                throw new IllegalStateException("未处理的操作符：" + comparison.operator());
        }
    }

    private static boolean ordered(Operator operator, AttributeType type, Object left, Object right) {
        if (operator == Operator.EQ) {
            return AttributeValues.same(type, left, right);
        }
        if (operator == Operator.NE) {
            return !AttributeValues.same(type, left, right);
        }
        // 解析器只允许 NUMBER 使用大小比较
        int order = ((BigDecimal) left).compareTo((BigDecimal) right);
        return switch (operator) {
            case GT -> order > 0;
            case GTE -> order >= 0;
            case LT -> order < 0;
            case LTE -> order <= 0;
            default -> throw new IllegalStateException("不是大小比较：" + operator);
        };
    }

    private static boolean anySame(AttributeType type, Object value, List<?> candidates) {
        for (Object candidate : candidates) {
            if (AttributeValues.same(type, value, candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 与 ltree 的 {@code <@} 一致：路径自身也算在子树下，且按整段标签匹配，{@code a.bc} 不在 {@code a.b} 下。 */
    private static boolean isUnder(String path, String ancestor) {
        return path.equals(ancestor) || path.startsWith(ancestor + ".");
    }

    /** SQL 三值逻辑（Kleene）。 */
    enum Truth {
        TRUE,
        FALSE,
        UNKNOWN;

        static Truth of(boolean value) {
            return value ? TRUE : FALSE;
        }

        Truth and(Truth other) {
            if (this == FALSE || other == FALSE) {
                return FALSE;
            }
            return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : TRUE;
        }

        Truth or(Truth other) {
            if (this == TRUE || other == TRUE) {
                return TRUE;
            }
            return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : FALSE;
        }

        Truth not() {
            return switch (this) {
                case TRUE -> FALSE;
                case FALSE -> TRUE;
                case UNKNOWN -> UNKNOWN;
            };
        }
    }
}
