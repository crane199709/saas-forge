package io.saasforge.iam.role.model;

import java.util.UUID;

/** Membership 与一个编码（角色编码或 Permission Code）的查询投影。 */
public record MembershipCodeRow(UUID membershipId, String code) {}
