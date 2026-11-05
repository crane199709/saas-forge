package io.saasforge.iam.mfa.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum MfaErrorCode implements ErrorCode {
    /** 已绑定 Authenticator 时再次开始或确认绑定；Recovery Code 只能通过重新生成另取一组。 */
    ALREADY_BOUND("mfa.alreadyBound", 409),
    /** 重新生成 Recovery Code、解绑或重置 MFA 时尚未绑定 Authenticator。 */
    NOT_BOUND("mfa.notBound", 409),
    /** 确认绑定时没有进行中的绑定：从未开始、已超时或已确认。 */
    BINDING_EXPIRED("mfa.bindingExpired", 400),
    /** 动态码错误、超出时钟容差或已被使用过。 */
    CODE_INVALID("mfa.codeInvalid", 400),
    /** 登录时提交的 Recovery Code 错误或已用过。 */
    RECOVERY_CODE_INVALID("mfa.recoveryCodeInvalid", 400),
    /** 要求 Operator 使用 MFA（{@code forge.iam.platform.mfa-required}）时解绑；此时只能更换 Authenticator。 */
    UNBIND_FORBIDDEN("mfa.unbindForbidden", 403),
    /** 按登录标识查找或重置 MFA 时，身份不存在或已删除。 */
    IDENTITY_NOT_FOUND("mfa.identityNotFound", 404);

    private final String code;
    private final int httpStatus;

    MfaErrorCode(String code, int httpStatus) {
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
