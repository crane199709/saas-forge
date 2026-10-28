package io.saasforge.iam.member.model;

import io.saasforge.iam.membership.model.MemberContactRow;
import java.util.UUID;

/**
 * 成员管理列表中的一个成员。
 *
 * @param account 被邀请人尚未设置账号时为 null
 * @param email Identity 的邮箱，只读：Tenant Admin 不能修改 Identity 的全局凭证与联系方式
 * @param phone Identity 的手机号，只读
 * @param status {@code invited}、{@code active}、{@code suspended} 或 {@code left}
 * @param owner 是否租户的 Owner
 */
public record ManagedMember(
        UUID membershipId,
        String account,
        String displayName,
        String email,
        String phone,
        String status,
        boolean owner) {
    public static ManagedMember of(MemberContactRow row, boolean owner) {
        return new ManagedMember(
                row.membershipId(), row.account(), row.displayName(), row.email(), row.phone(), row.status(), owner);
    }
}
