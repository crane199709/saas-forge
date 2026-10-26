package io.saasforge.sdk.abac;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一个可在 Data Policy 中引用的属性。
 *
 * @param name 带命名空间的全名，如 {@code subject.departmentPaths}、{@code resource.ownerId}
 * @param type 值类型
 * @param multiValued 是否为集合值；资源侧对应 PostgreSQL 数组列
 */
public record AttributeDefinition(String name, AttributeType type, boolean multiValued) {
    private static final Pattern NAME = Pattern.compile("(subject|resource|env)\\.[A-Za-z_][A-Za-z0-9_]*");

    /** 校验命名空间，属性名不合法时抛出 {@link IllegalArgumentException}。 */
    public AttributeDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("属性名必须形如 subject.x、resource.x 或 env.x：" + name);
        }
    }

    /**
     * 创建单值属性。
     *
     * @param name 带命名空间的全名
     * @param type 值类型
     * @return 单值属性定义
     */
    public static AttributeDefinition single(String name, AttributeType type) {
        return new AttributeDefinition(name, type, false);
    }

    /**
     * 创建集合值属性。
     *
     * @param name 带命名空间的全名
     * @param type 元素类型
     * @return 集合值属性定义
     */
    public static AttributeDefinition multi(String name, AttributeType type) {
        return new AttributeDefinition(name, type, true);
    }

    /**
     * 是否为资源属性；只有资源属性会被编译为表列，其余属性在编译期就能确定取值。
     *
     * @return 命名空间为 {@code resource} 时为 {@code true}
     */
    public boolean isResource() {
        return name.startsWith("resource.");
    }
}
