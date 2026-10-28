package io.saasforge.iam.identity.model;

import java.util.UUID;

// 身份与 Membership 为 active、租户为试用或正常的查询投影，只有这样的 Membership 才能成为 Active Tenant。
public record ActiveMembership(
        UUID identityId,
        String identityName,
        UUID tenantId,
        String tenantName,
        String tenantStatus,
        UUID membershipId,
        long version) {}
