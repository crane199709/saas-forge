package io.saasforge.platform.plan.model;

/**
 * iam 权限码目录中的一项，与 iam {@code GET /internal/permissions} 的响应一致，供 Feature Pack 选择。
 *
 * @param code 权限码
 * @param name 展示名称
 * @param service 申报该权限码的服务
 * @param group 展示分组，可为 null
 */
public record CatalogPermission(String code, String name, String service, String group) {}
