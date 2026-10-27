package io.saasforge.sdk.core.api;

/**
 * 校验失败时信封 {@code errors} 中的一项。
 *
 * @param field 字段名，与请求中的 JSON 字段或 query 参数名一致
 * @param code 校验错误码，如 {@code validation.notBlank}
 * @param userTip 面向最终用户的文案
 */
public record FieldError(String field, String code, String userTip) {}
