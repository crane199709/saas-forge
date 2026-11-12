package io.saasforge.iam.servicecredential.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum ServiceCredentialErrorCode implements ErrorCode {
    NOT_FOUND("serviceCredential.notFound", 404),
    INVALID_SERVICE("serviceCredential.invalidService", 400),
    NOT_ACTIVE("serviceCredential.notActive", 409),
    UNAVAILABLE("serviceCredential.unavailable", 503);
    private final String code;
    private final int httpStatus;

    ServiceCredentialErrorCode(String code, int httpStatus) {
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
