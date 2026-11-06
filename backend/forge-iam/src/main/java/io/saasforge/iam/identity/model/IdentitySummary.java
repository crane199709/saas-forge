package io.saasforge.iam.identity.model;

import java.util.UUID;

/** 按登录标识找到的身份：只有标识与展示名，不带 Membership 与凭证。 */
public record IdentitySummary(UUID identityId, String displayName) {}
