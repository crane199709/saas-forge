package io.saasforge.iam.membership.model;

import java.util.UUID;

// 变更后 Membership 的归属租户与新版本号，用于发布到 Redis。
public record MembershipChange(UUID tenantId, long version) {}
