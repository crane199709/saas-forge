package io.saasforge.iam.operator.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum OperatorErrorCode implements ErrorCode {
    /** Operator 不存在或已删除。 */
    NOT_FOUND("operator.notFound", 404),
    /** 账号已被未删除的 Operator 使用。 */
    ACCOUNT_TAKEN("operator.accountTaken", 409);

    private final String code;
    private final int httpStatus;

    OperatorErrorCode(String code, int httpStatus) {
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
