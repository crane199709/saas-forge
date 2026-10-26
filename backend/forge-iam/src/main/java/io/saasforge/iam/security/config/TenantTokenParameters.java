package io.saasforge.iam.security.config;

/** Token 交换适配器与 Token 定制器之间约定的附加 Token 请求参数。 */
public final class TenantTokenParameters {
    /** 指定目标 Membership；定制器只接受已认证身份自己的有效 Membership，否则以 access_denied 拒绝。 */
    public static final String MEMBERSHIP_ID = "membership_id";

    private TenantTokenParameters() {}
}
