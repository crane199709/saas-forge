package io.saasforge.iam.membership.model;

import java.util.UUID;

/**
 * 成员管理列表的查询投影：含 Identity 的联系方式，只供 Tenant Admin 识别成员，不可经租户修改。
 *
 * @param account 待激活 Identity 尚未设置账号时为 null
 * @param status Membership 状态，见 {@link MembershipStatus} 的常量
 */
public record MemberContactRow(
        UUID membershipId, String account, String displayName, String email, String phone, String status) {}
