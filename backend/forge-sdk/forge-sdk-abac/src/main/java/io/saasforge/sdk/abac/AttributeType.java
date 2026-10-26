package io.saasforge.sdk.abac;

/**
 * 属性的值类型，决定可用的操作符、字面量格式与 SQL 参数类型。
 *
 * <p>内存中的值表示：{@code STRING} 与 {@code LTREE} 为 {@link String}，{@code NUMBER} 为 {@link java.math.BigDecimal}，
 * {@code BOOLEAN} 为 {@link Boolean}，{@code UUID} 为 {@link java.util.UUID}。
 */
public enum AttributeType {
    /** 文本。 */
    STRING("text"),
    /** 数值；大小比较只对该类型开放，避免 Java 与数据库排序规则不一致。 */
    NUMBER("numeric"),
    /** 布尔。 */
    BOOLEAN("boolean"),
    /** UUID，如 Membership 主键。 */
    UUID("uuid"),
    /** PostgreSQL {@code ltree} 路径，如 Department 层级路径。 */
    LTREE("ltree");

    private final String sqlType;

    AttributeType(String sqlType) {
        this.sqlType = sqlType;
    }

    /**
     * 编译后的 SQL 中该类型参数的 PostgreSQL 类型名。
     *
     * @return 用于 {@code CAST(? AS ...)} 的类型名
     */
    public String sqlType() {
        return sqlType;
    }
}
