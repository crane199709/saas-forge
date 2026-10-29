package io.saasforge.sdk.abac;

import io.saasforge.sdk.core.ResourceManifest.DataAttribute;
import io.saasforge.sdk.core.ResourceManifest.DataResource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 由 Resource Manifest 中的 {@link DataResource} 建立的策略加载环境：平台提供的主体与环境属性、租户的 Extended Attribute
 * 加上资源声明的属性构成属性目录，资源属性映射到表列。iam 用它校验角色上的 Data Policy，SDK 用它编译与求值，两边按同一份目录判断策略是否合法。
 */
public final class DataResourceDefinition {
    /**
     * 平台为每个 Membership 提供的主体属性，取值约定见 {@code SubjectAttributes#toContext}。
     *
     * <p>Extended Attribute 的类型由各租户定义，不在固定目录中，见 {@link #extendedAttributes}。
     */
    public static final List<AttributeDefinition> SUBJECT_ATTRIBUTES = List.of(
            AttributeDefinition.single("subject.membershipId", AttributeType.UUID),
            AttributeDefinition.multi("subject.departmentPaths", AttributeType.LTREE),
            AttributeDefinition.single("subject.primaryDepartmentPath", AttributeType.LTREE),
            AttributeDefinition.multi("subject.positionIds", AttributeType.UUID));

    /** 平台提供的环境属性，取值见 {@link #environment}。 */
    public static final List<AttributeDefinition> ENVIRONMENT_ATTRIBUTES =
            List.of(AttributeDefinition.single("env.now", AttributeType.NUMBER));

    // Extended Attribute 只有这三种类型，见 iam 的扩展属性定义
    private static final Map<String, AttributeType> EXTENDED_TYPES =
            Map.of("string", AttributeType.STRING, "number", AttributeType.NUMBER, "boolean", AttributeType.BOOLEAN);

    private final DataResource resource;
    private final List<AttributeDefinition> resourceAttributes;
    private final AttributeCatalog catalog;
    private final Map<String, String> columns;

    private DataResourceDefinition(DataResource resource) {
        this.resource = resource;
        var attributes = new ArrayList<AttributeDefinition>();
        var columns = new LinkedHashMap<String, String>();
        for (DataAttribute attribute : resource.attributes()) {
            String name = "resource." + attribute.code();
            attributes.add(AttributeDefinition.single(name, type(attribute)));
            columns.put(name, attribute.column());
        }
        this.resourceAttributes = List.copyOf(attributes);
        this.catalog = catalog(Map.of());
        this.columns = Map.copyOf(columns);
    }

    /**
     * 租户 Extended Attribute 定义对应的主体属性 {@code subject.attributes.<编码>}。
     *
     * @param types 属性编码到类型（{@code string number boolean}）
     * @return 按编码排序的单值属性
     * @throws IllegalArgumentException 类型未知或编码不合法
     */
    public static List<AttributeDefinition> extendedAttributes(Map<String, String> types) {
        return types.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    AttributeType type = EXTENDED_TYPES.get(entry.getValue());
                    if (type == null) {
                        throw new IllegalArgumentException(
                                "Unknown type of extended attribute " + entry.getKey() + ": " + entry.getValue());
                    }
                    return AttributeDefinition.single("subject.attributes." + entry.getKey(), type);
                })
                .toList();
    }

    /**
     * 请求时刻的环境属性值，与主体上下文合并后用于求值与编译。
     *
     * <ul>
     *   <li>{@code env.now}：NUMBER，Unix 时间戳（秒）</li>
     * </ul>
     *
     * @param now 请求时刻；同一请求的求值与编译使用同一时刻，结论才一致
     * @return 键为全名的环境上下文
     */
    public static Map<String, Object> environment(Instant now) {
        return Map.of("env.now", now.getEpochSecond());
    }

    /**
     * 为清单中声明的数据资源建立定义。
     *
     * @param resource 已通过清单校验的数据资源
     * @return 定义
     * @throws IllegalArgumentException 属性类型未知或属性重复
     */
    public static DataResourceDefinition of(DataResource resource) {
        return new DataResourceDefinition(resource);
    }

    /**
     * 资源编码。
     *
     * @return 如 {@code order}
     */
    public String code() {
        return resource.code();
    }

    /**
     * 资源所在的表，已转为小写。
     *
     * @return 表名
     */
    public String table() {
        return resource.table().toLowerCase(Locale.ROOT);
    }

    /**
     * 不含 Extended Attribute 的属性目录，用于判断预设是否可用。
     *
     * @return 平台主体属性、环境属性与本资源属性的目录
     */
    public AttributeCatalog catalog() {
        return catalog;
    }

    /**
     * 策略可引用的全部属性。
     *
     * @param extendedAttributeTypes 租户 Extended Attribute 编码到类型
     * @return 平台主体属性、扩展属性、环境属性与本资源属性的目录
     * @throws IllegalArgumentException 扩展属性类型未知
     */
    public AttributeCatalog catalog(Map<String, String> extendedAttributeTypes) {
        var definitions = new ArrayList<>(SUBJECT_ATTRIBUTES);
        definitions.addAll(extendedAttributes(extendedAttributeTypes));
        definitions.addAll(ENVIRONMENT_ATTRIBUTES);
        definitions.addAll(resourceAttributes);
        return AttributeCatalog.of(definitions);
    }

    /**
     * 本资源的属性，顺序同清单声明。
     *
     * @return 全名形如 {@code resource.ownerId} 的单值属性
     */
    public List<AttributeDefinition> resourceAttributes() {
        return resourceAttributes;
    }

    /**
     * 资源属性全名到列引用的映射，供 {@link PolicySqlCompiler} 使用。
     *
     * @param qualifier 列引用的限定名（表别名或表名），为 null 时不加限定
     * @return 如 {@code resource.ownerId → o.owner_id}
     */
    public Map<String, String> columns(String qualifier) {
        if (qualifier == null) return columns;
        var qualified = new HashMap<String, String>();
        columns.forEach((name, column) -> qualified.put(name, qualifier + "." + column));
        return qualified;
    }

    /**
     * 解析并校验本资源上不引用 Extended Attribute 的 Data Policy，如 Data Scope 预设。
     *
     * @param json JSON 表达式树
     * @return 表达式树
     * @throws PolicyValidationException 结构不合法，或引用了本资源未声明的属性
     */
    public Expression parse(String json) {
        return PolicyParser.parse(json, catalog);
    }

    /**
     * 解析并校验本资源上的 Data Policy，可引用租户的 Extended Attribute。
     *
     * @param json JSON 表达式树
     * @param extendedAttributeTypes 租户 Extended Attribute 编码到类型
     * @return 表达式树
     * @throws PolicyValidationException 结构不合法，或引用了未声明的属性
     */
    public Expression parse(String json, Map<String, String> extendedAttributeTypes) {
        return PolicyParser.parse(json, catalog(extendedAttributeTypes));
    }

    /**
     * 本资源能使用的 Data Scope 预设：预设引用的资源属性都已声明且类型相符。
     *
     * @return 按 {@link DataScope} 声明顺序排列
     */
    public Set<DataScope> availableScopes() {
        return Arrays.stream(DataScope.values())
                .filter(scope -> {
                    try {
                        scope.expression(catalog);
                        return true;
                    } catch (PolicyValidationException unavailable) {
                        return false;
                    }
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 把按属性编码给出的资源对象转为求值用的资源上下文。
     *
     * @param attributes 属性编码到值，如 {@code ownerId → <uuid>}；未给出的属性视为 null
     * @return 键为全名的资源上下文
     * @throws IllegalArgumentException 给出了本资源未声明的属性
     */
    public Map<String, Object> resourceValues(Map<String, ?> attributes) {
        var values = new HashMap<String, Object>();
        attributes.forEach((code, value) -> {
            String name = "resource." + code;
            if (!columns.containsKey(name)) {
                throw new IllegalArgumentException("Data Resource " + code() + " does not declare attribute " + code);
            }
            values.put(name, value);
        });
        return values;
    }

    private static AttributeType type(DataAttribute attribute) {
        try {
            return AttributeType.valueOf(attribute.type().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Unknown type of attribute " + attribute.code() + ": " + attribute.type());
        }
    }
}
