package io.saasforge.iam.role.model;

import java.util.UUID;

/** 角色上的一条 Data Policy 查询投影。 */
public record RoleDataPolicyRow(UUID roleId, String resource, String scope) {}
