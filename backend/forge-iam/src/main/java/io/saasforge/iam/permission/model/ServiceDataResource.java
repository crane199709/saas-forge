package io.saasforge.iam.permission.model;

import io.saasforge.sdk.core.ResourceManifest.DataResource;

/**
 * 某服务当前清单中声明的 Data Resource。
 *
 * @param service 声明该资源的服务
 * @param resource 资源及其属性
 */
public record ServiceDataResource(String service, DataResource resource) {}
