package io.saasforge.sdk.security;

/**
 * 固定策略的 Masking Rule；通过 {@link MaskingEngine#validate} 在保存或启动时校验。
 *
 * @param strategy 固定策略名；不接受表达式或正则
 * @param keepFirst 仅 {@code keepFirst} 使用的保留码点数，其他策略必须为 null
 * @param replacement 仅 {@code replace} 使用的非空替换文本，其他策略必须为 null
 */
public record MaskingRule(String strategy, Integer keepFirst, String replacement) {}
