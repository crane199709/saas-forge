package io.saasforge.iam.tenant.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum TenantErrorCode implements ErrorCode {
    CURRENT_IP_NOT_INCLUDED("tenant.currentIpNotIncluded", 400),
    NOT_FOUND("tenant.notFound", 404);

    private final String code;
    private final int httpStatus;

    TenantErrorCode(String code, int httpStatus) {
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
