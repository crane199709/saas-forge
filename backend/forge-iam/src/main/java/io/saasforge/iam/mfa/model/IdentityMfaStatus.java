package io.saasforge.iam.mfa.model;

import java.util.UUID;

/**
 * 平台按登录标识查到的身份及其是否已绑定 Authenticator；只供 MFA Reset 使用，不带 Membership。
 *
 * @param mfaBound 是否已绑定 Authenticator
 */
public record IdentityMfaStatus(UUID identityId, String displayName, boolean mfaBound) {}
