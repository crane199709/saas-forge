package io.saasforge.iam.member.model;

import io.saasforge.iam.membership.model.MembershipStatus;

/**
 * 一次邀请的结果。
 *
 * @param membership 被邀请人的 Membership，状态为 invited
 * @param resent 是否给仍为 invited 的 Membership 重发；新邀请与已离开成员的重新邀请为 false
 */
public record MemberInvitation(MembershipStatus membership, boolean resent) {}
