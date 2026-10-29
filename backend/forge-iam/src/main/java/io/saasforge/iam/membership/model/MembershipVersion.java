package io.saasforge.iam.membership.model;

import java.util.UUID;

// 批量递增后某个 Membership 的新版本号，用于发布到 Redis。
public record MembershipVersion(UUID membershipId, long version) {}
