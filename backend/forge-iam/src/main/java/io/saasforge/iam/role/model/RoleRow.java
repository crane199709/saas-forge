package io.saasforge.iam.role.model;

import java.util.UUID;

/**
 * 角色查询投影。
 *
 * @param builtin 是否预置角色（Owner、Tenant Admin）
 * @param owner 是否 Owner 角色
 * @param memberCount 持有该角色的未删除 Membership 数
 */
public record RoleRow(UUID id, String code, String name, boolean builtin, boolean owner, long memberCount) {}
