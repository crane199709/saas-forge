package io.saasforge.iam.role.model;

/**
 * Plan 变更对租户成员的影响，供 platform 预览更换 Plan。
 *
 * @param memberCount 占用成员数配额的 Membership 数量，口径与邀请时的配额校验一致（未离开的都占名额）
 * @param affectedMemberships 状态正常、会因本次变更失去能力的 Membership 数量
 */
public record PlanImpactResponse(long memberCount, long affectedMemberships) {}
