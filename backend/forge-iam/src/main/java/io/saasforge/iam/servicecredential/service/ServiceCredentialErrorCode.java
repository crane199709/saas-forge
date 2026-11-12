package io.saasforge.iam.servicecredential.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum ServiceCredentialErrorCode implements ErrorCode {
    ACTOR_REQUIRED("service-credential.actorRequired", 400),
    INVALID_STATUS("service-credential.invalidStatus", 400),
    NOT_FOUND("service-credential.notFound", 404),
    INVALID_SERVICE("service-credential.invalidService", 400),
    NOT_ACTIVE("service-credential.notActive", 409),
    UNAVAILABLE("service-credential.unavailable", 503);
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
