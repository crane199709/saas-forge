package io.saasforge.iam.identity.model;

import java.util.UUID;

// 身份、Membership 与租户三者均为 active 的查询投影，只有这样的 Membership 才能成为 Active Tenant。
public record ActiveMembership(
        UUID identityId, String identityName, UUID tenantId, String tenantName, UUID membershipId, long version) {}
