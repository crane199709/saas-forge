package io.saasforge.platform.tenant.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum TenantErrorCode implements ErrorCode {
    /** 租户不存在或已删除。 */
    NOT_FOUND("tenant.notFound", 404),
    /** 该租户没有这个服务的初始化记录。 */
    INITIALIZATION_NOT_FOUND("tenant.initializationNotFound", 404),
    /** 只有失败的初始化可以重试：pending 的仍在进行，succeeded 的无需重试。 */
    INITIALIZATION_NOT_FAILED("tenant.initializationNotFailed", 409);

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
