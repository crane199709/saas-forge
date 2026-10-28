package io.saasforge.iam.membership.model;

import java.util.UUID;

/** 邀请令牌指向的 Membership，连同被邀请的身份与租户。 */
public record Invitation(
        UUID membershipId,
        String membershipStatus,
        UUID tenantId,
        String tenantName,
        UUID identityId,
        String identityStatus) {}
