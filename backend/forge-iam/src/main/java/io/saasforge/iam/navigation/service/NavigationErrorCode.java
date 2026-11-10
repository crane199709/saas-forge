package io.saasforge.iam.navigation.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum NavigationErrorCode implements ErrorCode {
    INVALID_LAYOUT("navigation.invalidLayout", 400);

    private final String code;
    private final int httpStatus;

    NavigationErrorCode(String code, int httpStatus) {
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
