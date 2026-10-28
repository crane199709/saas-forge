package io.saasforge.iam.identity.model;

/** identity.status 的取值。 */
public final class IdentityStatus {
    /** 由邀请建立、尚未设置账号与密码，不能登录。 */
    public static final String PENDING = "pending";

    private IdentityStatus() {}
}
