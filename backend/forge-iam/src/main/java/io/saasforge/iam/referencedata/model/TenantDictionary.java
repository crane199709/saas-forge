package io.saasforge.iam.referencedata.model;

import java.util.List;

/**
 * 某租户的一个 Dictionary：服务声明的内置项合并租户的设置与自定义项。
 *
 * @param service 声明字典的服务
 * @param code 字典编码
 * @param name 字典名称
 * @param extensible 是否为 Extensible Dictionary；否则对租户只读
 * @param items 全部字典项（含不可选项）：未下线的项按排序与编码排列，下线项排在最后
 */
public record TenantDictionary(
        String service, String code, String name, boolean extensible, List<TenantDictionaryItem> items) {
    public TenantDictionary {
        items = List.copyOf(items);
    }
}
