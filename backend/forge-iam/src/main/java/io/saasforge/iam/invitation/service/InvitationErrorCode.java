package io.saasforge.iam.invitation.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum InvitationErrorCode implements ErrorCode {
    /** 邀请令牌不存在、已过期或已被接受。 */
    INVALID("invitation.invalid", 404),
    /** 被邀请人尚无可登录的 Identity，接受邀请时必须设置账号与密码。 */
    CREDENTIALS_REQUIRED("invitation.credentialsRequired", 400);

    private final String code;
    private final int httpStatus;

    InvitationErrorCode(String code, int httpStatus) {
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
