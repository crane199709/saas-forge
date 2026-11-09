package io.saasforge.iam.role.model;

import java.util.List;

/**
 * 替换角色 Data Policy 前的影响：只列出持有该角色的成员，不判断具体数据行的变化。
 *
 * @param affectedCount 持有该角色的 active Membership 数量
 * @param members 其中按 Membership 标识排序的前 20 名
 */
public record RoleDataPolicyImpactResponse(long affectedCount, List<AffectedMember> members) {}
