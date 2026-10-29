package io.saasforge.platform.subapp.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum SubAppErrorCode implements ErrorCode {
    /** Sub-App 不存在或已删除。 */
    NOT_FOUND("subApp.notFound", 404),
    /** 编码已被另一个未删除的 Sub-App 使用。 */
    CODE_TAKEN("subApp.codeTaken", 409);

    private final String code;
    private final int httpStatus;

    SubAppErrorCode(String code, int httpStatus) {
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
