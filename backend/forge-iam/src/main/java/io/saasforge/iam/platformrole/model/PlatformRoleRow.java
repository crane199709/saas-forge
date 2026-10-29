package io.saasforge.iam.platformrole.model;

import java.util.UUID;

/**
 * 平台角色查询投影。
 *
 * @param builtin 是否预置的超级管理员
 * @param operatorCount 持有该角色的未删除 Operator 数
 */
public record PlatformRoleRow(UUID id, String code, String name, boolean builtin, long operatorCount) {}
