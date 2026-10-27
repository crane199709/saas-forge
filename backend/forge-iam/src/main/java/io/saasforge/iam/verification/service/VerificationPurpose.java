package io.saasforge.iam.verification.service;

/** 验证码用途；不同用途的验证码互不通用。 */
public enum VerificationPurpose {
    REGISTER,
    LOGIN
}
