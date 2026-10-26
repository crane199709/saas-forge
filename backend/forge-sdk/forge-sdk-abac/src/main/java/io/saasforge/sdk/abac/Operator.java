package io.saasforge.sdk.abac;

import java.util.Arrays;
import java.util.Optional;

/** 比较节点的操作符；JSON 中使用 {@link #jsonName()}。 */
public enum Operator {
    /** 等于。 */
    EQ("eq"),
    /** 不等于。 */
    NE("ne"),
    /** 属于给定集合。 */
    IN("in"),
    /** 不属于给定集合。 */
    NIN("nin"),
    /** 大于，仅限数值。 */
    GT("gt"),
    /** 大于等于，仅限数值。 */
    GTE("gte"),
    /** 小于，仅限数值。 */
    LT("lt"),
    /** 小于等于，仅限数值。 */
    LTE("lte"),
    /** 集合值属性包含给定元素。 */
    CONTAINS("contains"),
    /** 属性值为空；不带 {@code value}。 */
    IS_NULL("is_null"),
    /** {@code ltree} 路径在给定路径（或任一路径）的子树下，含路径自身，对应 PostgreSQL {@code <@}。 */
    UNDER("under");

    private final String jsonName;

    Operator(String jsonName) {
        this.jsonName = jsonName;
    }

    /**
     * JSON 表达式树中的操作符名。
     *
     * @return 如 {@code is_null}
     */
    public String jsonName() {
        return jsonName;
    }

    /**
     * 按 JSON 名查找操作符。
     *
     * @param jsonName JSON 中的操作符名
     * @return 操作符；未知时为空
     */
    public static Optional<Operator> fromJson(String jsonName) {
        return Arrays.stream(values())
                .filter(operator -> operator.jsonName.equals(jsonName))
                .findFirst();
    }
}
