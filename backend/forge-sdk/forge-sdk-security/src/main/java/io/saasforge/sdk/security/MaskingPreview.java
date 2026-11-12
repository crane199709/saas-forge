package io.saasforge.sdk.security;

/**
 * 统一引擎的预览结果；失败原因只由固定错误码生成，不保存样例原文。
 *
 * @param success 是否成功（合法空值也成功）
 * @param value 成功时的脱敏结果；失败时为 null
 * @param errorCode 失败时的稳定错误码，成功时为 null
 * @param errorMessage 失败时不带原文的固定原因，成功时为 null
 */
public record MaskingPreview(boolean success, String value, String errorCode, String errorMessage) {}
