package io.saasforge.iam.role.model;

import java.util.List;
import java.util.UUID;

/**
 * 租户内的一个成员及其角色。
 *
 * @param status Membership 状态
 * @param roleIds 持有的角色
 */
public record MemberRoles(UUID membershipId, String account, String displayName, String status, List<UUID> roleIds) {}
