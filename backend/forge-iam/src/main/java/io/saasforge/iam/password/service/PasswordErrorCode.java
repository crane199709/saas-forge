package io.saasforge.iam.password.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum PasswordErrorCode implements ErrorCode {
    /** 新密码短于所在 Realm 的最小长度；参数为最小长度。 */
    TOO_SHORT("password.tooShort", 400),
    /** 新密码超过 72 字节。 */
    TOO_LONG("password.tooLong", 400),
    /** 新密码在内置弱密码清单中。 */
    TOO_COMMON("password.tooCommon", 400),
    /** 新密码与账号、邮箱或手机号相同。 */
    SAME_AS_IDENTIFIER("password.sameAsIdentifier", 400),
    /** 自助改密码时原密码错误。 */
    CURRENT_INVALID("password.currentInvalid", 400),
    /** 忘记密码重设时没有有效的重设凭证：未通过验证码、已超时或已用过，须重新获取验证码。 */
    RESET_EXPIRED("password.resetExpired", 400),
    /** 已绑定 Authenticator 的身份忘记密码重设时未提供动态码或 Recovery Code。 */
    MFA_REQUIRED("password.mfaRequired", 400);

    private final String code;
    private final int httpStatus;

    PasswordErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
