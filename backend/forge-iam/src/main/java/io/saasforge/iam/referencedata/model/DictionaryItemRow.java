package io.saasforge.iam.referencedata.model;

/**
 * 租户对某个字典项的设置。
 *
 * @param service 声明字典的服务
 * @param dictionary 字典编码
 * @param code 字典项编码，自定义项带 {@code custom.} 前缀
 * @param custom 是否为租户自定义项
 * @param name 显示名；内置项为 null 表示沿用声明
 * @param sortOrder 排序；内置项为 null 表示沿用声明顺序
 * @param enabled 是否启用
 */
public record DictionaryItemRow(
        String service,
        String dictionary,
        String code,
        boolean custom,
        String name,
        Integer sortOrder,
        boolean enabled) {}
