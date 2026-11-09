package io.saasforge.iam.role.model;

import java.util.List;

/**
 * 替换角色权限码前的净影响。
 *
 * @param affectedCount 净失去至少一个 Permission Code 的 active Membership 数量
 * @param members 其中按 Membership 标识排序的前 20 名
 * @param lostPermissions 这些成员净失去的 Permission Code 的并集，按编码排序
 */
public record RolePermissionImpactResponse(
        long affectedCount, List<AffectedMember> members, List<String> lostPermissions) {}
