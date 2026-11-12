package io.saasforge.sdk.security;

/**
 * 当前租户与服务的 Sensitive Field 生效规则，不包含原文。
 *
 * <p>字段路径和数据分级由 Resource Manifest 的统一校验负责，提供方不得把规则缺失的已声明字段删除。
 *
 * @param path 已登记的服务内字段路径
 * @param grade 已通过 Manifest 校验的 {@code L1 L2 L3 L4} 数据分级
 * @param rule 生效的固定规则；null 表示已声明但规则缺失，执行通道必须失败关闭
 * @param exportAllowed 是否允许导出；提供方须应用 L4 默认禁止导出的限制
 */
public record EffectiveFieldPolicy(String path, String grade, MaskingRule rule, boolean exportAllowed) {}
