package io.saasforge.iam.registration.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum RegistrationErrorCode implements ErrorCode {
    /** 账号已被未删除的身份占用。 */
    ACCOUNT_TAKEN("registration.accountTaken", 409),
    /** 邮箱或手机号已被未删除的身份占用。 */
    CONTACT_TAKEN("registration.contactTaken", 409);

    private final String code;
    private final int httpStatus;

    RegistrationErrorCode(String code, int httpStatus) {
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
