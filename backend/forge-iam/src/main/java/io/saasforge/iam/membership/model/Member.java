package io.saasforge.iam.membership.model;

import java.util.Map;
import java.util.UUID;

/**
 * 租户内的一个成员。
 *
 * @param membershipId Membership 标识
 * @param account Identity 账号
 * @param displayName Identity 昵称
 * @param status Membership 状态
 * @param attributes Extended Attribute 值，按属性编码
 */
public record Member(
        UUID membershipId, String account, String displayName, String status, Map<String, Object> attributes) {}
