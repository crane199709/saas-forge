package io.saasforge.iam.role.model;

import java.util.UUID;

/** Membership 持有的一个角色。 */
public record MembershipRoleRow(UUID membershipId, UUID roleId) {}
