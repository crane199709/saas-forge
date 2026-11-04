package io.saasforge.iam.referencedata.model;

/**
 * 合并租户设置后的字典项。
 *
 * @param code 字典项编码，自定义项带 {@code custom.} 前缀
 * @param name 生效的显示名
 * @param sort 生效的排序，越小越靠前
 * @param custom 是否为租户自定义项
 * @param status {@value #ENABLED}（可选）、{@value #DISABLED}（租户已停用）或 {@value #RETIRED}（服务新版本已删除该内置项，
 *     或字典不再可扩展时的自定义项）；后两者不可选，仍返回供历史数据显示名称
 */
public record TenantDictionaryItem(String code, String name, int sort, boolean custom, String status) {
    /** 可选。 */
    public static final String ENABLED = "enabled";

    /** 租户已停用，可再启用。 */
    public static final String DISABLED = "disabled";

    /** 已下线，租户不能再修改或启用。 */
    public static final String RETIRED = "retired";

    /** 是否为可选项：新写入的业务数据只能使用可选项。 */
    public boolean selectable() {
        return ENABLED.equals(status);
    }
}
