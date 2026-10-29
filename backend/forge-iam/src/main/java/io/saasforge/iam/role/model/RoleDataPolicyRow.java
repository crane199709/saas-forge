package io.saasforge.iam.role.model;

import java.util.UUID;

/** 角色上的一条 Data Policy 查询投影；{@code condition} 只在自定义条件时有值。 */
public record RoleDataPolicyRow(UUID roleId, String resource, String scope, String condition) {}
