package io.saasforge.sdk.abac;

import java.util.Collection;
import java.util.List;

/**
 * 已通过校验的 Data Policy 表达式树，由 {@link PolicyParser} 或 {@link DataScope} 产生。
 *
 * <p>同一棵树交给 {@link PolicySqlCompiler} 与 {@link PolicyEvaluator} 得到的结论一致（ADR-0006）。
 */
public sealed interface Expression {
    /**
     * 合并多个策略为并集；多个 Role 的 Data Policy 按此规则叠加。没有任何策略时结果恒为假，即默认拒绝。
     *
     * @param policies 待合并的策略
     * @return 任一策略成立即成立的表达式
     */
    static Expression union(Collection<? extends Expression> policies) {
        return new Or(List.copyOf(policies));
    }

    /**
     * 全部子节点成立；没有子节点时恒为真。
     *
     * @param children 子节点
     */
    record And(List<Expression> children) implements Expression {
        /** 拷贝为不可变列表。 */
        public And {
            children = List.copyOf(children);
        }
    }

    /**
     * 任一子节点成立；没有子节点时恒为假。
     *
     * @param children 子节点
     */
    record Or(List<Expression> children) implements Expression {
        /** 拷贝为不可变列表。 */
        public Or {
            children = List.copyOf(children);
        }
    }

    /**
     * 子节点取反。
     *
     * @param child 子节点
     */
    record Not(Expression child) implements Expression {}

    /**
     * 比较节点 {@code {attr, op, value}}。
     *
     * @param attribute 左侧属性
     * @param operator 操作符
     * @param operand 右侧取值；{@link Operator#IS_NULL} 时为 {@code null}
     */
    record Comparison(AttributeDefinition attribute, Operator operator, Operand operand) implements Expression {}
}
