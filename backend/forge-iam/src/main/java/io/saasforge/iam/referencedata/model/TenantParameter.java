package io.saasforge.iam.referencedata.model;

/**
 * 某租户的一个 Parameter：声明的类型、约束与默认值，以及合并覆盖值后的生效值。取值的 JSON 写法同 Resource Manifest。
 *
 * @param service 声明参数的服务
 * @param code 参数编码
 * @param name 展示名称
 * @param type 类型：{@code string integer decimal duration boolean dictionary}
 * @param dictionary {@code dictionary} 类型取值所属的字典编码（同一服务），其他类型为 null
 * @param defaultValue 默认值
 * @param min 最小值（含），可为 null
 * @param max 最大值（含），可为 null
 * @param maxLength {@code string} 类型的最大长度，可为 null
 * @param tenantEditable 租户是否可覆盖
 * @param value 生效值：覆盖值有效时为覆盖值，否则为默认值
 * @param overridden 生效值是否来自租户的覆盖
 */
public record TenantParameter(
        String service,
        String code,
        String name,
        String type,
        String dictionary,
        Object defaultValue,
        Object min,
        Object max,
        Integer maxLength,
        boolean tenantEditable,
        Object value,
        boolean overridden) {}
