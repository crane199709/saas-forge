package io.saasforge.iam.announcement.service;

import io.saasforge.sdk.core.api.ErrorCode;

/** 公告对外业务错误。 */
public enum AnnouncementErrorCode implements ErrorCode {
    NOT_FOUND("announcement.notFound", 404),
    INVALID_TARGET("announcement.invalidTarget", 400);
    private final String code;
    private final int httpStatus;

    AnnouncementErrorCode(String code, int httpStatus) {
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
