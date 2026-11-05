package io.saasforge.iam.session.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum SessionErrorCode implements ErrorCode {
    /** 账号或密码错误、登录会话或刷新凭证失效；不区分原因，避免暴露账号是否存在。 */
    AUTH_FAILED("auth.failed", 401),
    /** 请求进入的 Membership 不属于当前身份，或其本身、所在租户不是 active。 */
    TENANT_ACCESS_DENIED("auth.tenantAccessDenied", 403),
    /** 提交 MFA 时没有有效的待 MFA 状态：未通过 First Factor、已超时或已完成，须重新提交 First Factor。 */
    MFA_EXPIRED("auth.mfaExpired", 401),
    /**
     * 需要 MFA 而本次认证未完成：目标 Tenant 提出了 MFA Requirement 而授权未完成 MFA，拒绝签发进入该租户的 Token（可当场绑定后重试）；
     * 或配置要求 Operator 登录必须经过 MFA（Operator 侧由账号安全工单 04 改为当场绑定）。
     */
    MFA_REQUIRED("auth.mfaRequired", 403);

    private final String code;
    private final int httpStatus;

    SessionErrorCode(String code, int httpStatus) {
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
