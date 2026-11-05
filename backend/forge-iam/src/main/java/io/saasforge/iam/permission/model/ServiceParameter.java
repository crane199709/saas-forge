package io.saasforge.iam.permission.model;

import io.saasforge.sdk.core.ResourceManifest.Parameter;

/**
 * 某服务当前清单中声明的 Parameter。
 *
 * @param service 声明该参数的服务
 * @param parameter 参数及其默认值与约束
 */
public record ServiceParameter(String service, Parameter parameter) {}
