package io.saasforge.iam.referencedata.model;

/** 租户覆盖快照中的字典项；内置项的 name、sort 为 null 表示沿用服务声明，不存储声明默认值。 */
public record DictionaryHistoryItem(String code, boolean custom, String name, Integer sort, boolean enabled) {}
