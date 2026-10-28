package io.saasforge.iam.permission.model;

/**
 * Permission Code 查询投影。
 *
 * @param code 权限码
 * @param name 展示名称
 * @param service 申报该权限码的服务
 * @param group 展示分组，可为 null
 */
public record PermissionRow(String code, String name, String service, String group) {}
