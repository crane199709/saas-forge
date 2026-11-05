package io.saasforge.sdk.referencedata;

/**
 * 合并当前租户设置后的字典项。
 *
 * @param code 字典项编码，业务数据保存它；租户自定义项带 {@code custom.} 前缀
 * @param name 生效的显示名，仅供展示，接口响应不返回名称
 * @param sort 生效的排序，越小越靠前
 * @param custom 是否为租户自定义项
 * @param status {@code enabled}（可选）、{@code disabled}（租户已停用）或 {@code retired}（已下线）；只有可选项能用于新写入
 */
public record TenantDictionaryItem(String code, String name, int sort, boolean custom, String status) {
    // 与 iam 返回的状态取值一致。
    private static final String ENABLED = "enabled";

    /**
     * 是否为可选项。
     *
     * @return 新写入的业务数据能否使用该编码
     */
    public boolean selectable() {
        return ENABLED.equals(status);
    }
}
