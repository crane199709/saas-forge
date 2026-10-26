package io.saasforge.sdk.abac;

/**
 * 编译后 SQL 中一个 {@code ?} 占位符对应的参数。
 *
 * <p>SQL 中已写明 {@code CAST(? AS <type>)} 或 {@code CAST(? AS <type>[])}，绑定方按 {@link #type()} 与 {@link #array()}
 * 设置 JDBC 参数即可。
 *
 * @param type 参数（或数组元素）的类型
 * @param array 是否为数组参数；为 {@code true} 时 {@link #value()} 是 {@link java.util.List}
 * @param value 按 {@link AttributeType} 约定表示的值，单值可为 {@code null}
 */
public record SqlParameter(AttributeType type, boolean array, Object value) {}
