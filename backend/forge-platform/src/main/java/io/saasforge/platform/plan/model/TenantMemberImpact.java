package io.saasforge.platform.plan.model;

/**
 * iam 统计的 Plan 变更对租户成员的影响。
 *
 * @param memberCount 占用成员数配额的 Membership 数量
 * @param affectedMemberships 状态正常、会因本次变更失去能力的 Membership 数量
 */
public record TenantMemberImpact(long memberCount, long affectedMemberships) {}
