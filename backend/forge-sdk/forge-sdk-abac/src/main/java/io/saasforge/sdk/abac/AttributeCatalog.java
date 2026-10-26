package io.saasforge.sdk.abac;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 加载 Data Policy 时可引用的全部属性及其类型；策略中出现目录以外的属性即为非法。
 *
 * <p>主体与环境属性由平台提供，资源属性来自 Business Service 声明的 Data Resource。
 */
public final class AttributeCatalog {
    private final Map<String, AttributeDefinition> attributes;

    private AttributeCatalog(Map<String, AttributeDefinition> attributes) {
        this.attributes = attributes;
    }

    /**
     * 由属性定义创建目录。
     *
     * @param definitions 属性定义，名称不得重复
     * @return 不可变目录
     */
    public static AttributeCatalog of(Collection<AttributeDefinition> definitions) {
        var map = new LinkedHashMap<String, AttributeDefinition>();
        for (var definition : definitions) {
            if (map.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalArgumentException("属性重复声明：" + definition.name());
            }
        }
        return new AttributeCatalog(Map.copyOf(map));
    }

    /**
     * 按全名查找属性。
     *
     * @param name 带命名空间的全名
     * @return 属性定义；未声明时为空
     */
    public Optional<AttributeDefinition> find(String name) {
        return Optional.ofNullable(attributes.get(name));
    }
}
