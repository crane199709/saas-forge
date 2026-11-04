package io.saasforge.iam.referencedata.model;

/**
 * 租户对某个 Parameter 的覆盖值。
 *
 * @param service 声明参数的服务
 * @param code 参数编码
 * @param value 覆盖值的 JSON
 */
public record ParameterOverrideRow(String service, String code, String value) {}
