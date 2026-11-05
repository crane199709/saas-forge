package io.saasforge.iam.password.model;

/**
 * 忘记密码时验证码通过后签发的一次性重设凭证。
 *
 * @param resetToken 提交新密码时携带，有效期 10 分钟，设置成功后作废
 * @param mfaRequired 身份已绑定 Authenticator，提交新密码时须同时提供动态码或 Recovery Code
 */
public record ResetTicket(String resetToken, boolean mfaRequired) {
    @Override
    public String toString() {
        return "ResetTicket[mfaRequired=" + mfaRequired + "]";
    }
}
