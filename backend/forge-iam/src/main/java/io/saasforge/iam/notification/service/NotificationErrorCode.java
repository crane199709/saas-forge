package io.saasforge.iam.notification.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum NotificationErrorCode implements ErrorCode {
    NOT_FOUND("notification.notFound", 404);
    private final String code;
    private final int httpStatus;

    NotificationErrorCode(String code, int httpStatus) {
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
