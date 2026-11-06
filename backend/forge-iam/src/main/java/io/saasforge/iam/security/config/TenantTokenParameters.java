package io.saasforge.iam.security.config;

/** Token 交换适配器与 Token 定制器之间约定的附加 Token 请求参数与错误码。 */
public final class TenantTokenParameters {
    /** 指定目标 Membership；定制器只接受已认证身份自己的有效 Membership，否则以 access_denied 拒绝。 */
    public static final String MEMBERSHIP_ID = "membership_id";

    /** 目标租户提出了 MFA Requirement 而授权未完成 MFA 时，定制器拒绝签发使用的 OAuth2 错误码。 */
    public static final String MFA_REQUIRED = "mfa_required";

    private TenantTokenParameters() {}
}
