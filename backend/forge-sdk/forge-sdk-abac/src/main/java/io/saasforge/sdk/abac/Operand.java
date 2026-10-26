package io.saasforge.sdk.abac;

import java.util.List;

/** 比较节点右侧的取值：字面量或对主体、环境属性的引用。 */
public sealed interface Operand {
    /**
     * 字面量，已按目标属性类型规范化。
     *
     * @param value 单值，或集合操作符下的不可变 {@link List}；元素均不为 {@code null}
     */
    record Literal(Object value) implements Operand {}

    /**
     * 引用主体或环境属性，求值与编译时从上下文取值，如当前成员的部门路径集合。
     *
     * @param attribute 被引用的属性，命名空间不是 {@code resource}
     */
    record AttributeRef(AttributeDefinition attribute) implements Operand {}
}
