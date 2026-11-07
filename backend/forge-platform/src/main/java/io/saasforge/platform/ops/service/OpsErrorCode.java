package io.saasforge.platform.ops.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum OpsErrorCode implements ErrorCode {
    CATALOG_UNAVAILABLE("ops.catalogUnavailable", 503),
    SERVICE_UNAVAILABLE("ops.serviceUnavailable", 503),
    SERVICE_NOT_FOUND("ops.serviceNotFound", 404),
    DEAD_EVENT_NOT_FOUND("ops.deadEventNotFound", 404),
    DEAD_EVENT_ALREADY_HANDLED("ops.deadEventAlreadyHandled", 409),
    DEAD_EVENT_REPLAY_FAILED("ops.deadEventReplayFailed", 409),
    REASON_REQUIRED("ops.reasonRequired", 400);
    private final String code;
    private final int status;

    OpsErrorCode(String code, int status) {
        this.code = code;
        this.status = status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int httpStatus() {
        return status;
    }
}
