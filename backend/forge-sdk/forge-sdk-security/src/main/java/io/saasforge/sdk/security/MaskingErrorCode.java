package io.saasforge.sdk.security;

import io.saasforge.sdk.core.api.ErrorCode;

/** 固定脱敏规则的安全错误码，不携带输入值或规则参数。 */
public enum MaskingErrorCode implements ErrorCode {
    /** 规则缺失、参数缺失或包含不属于该策略的参数。 */
    INVALID_RULE("field-masking.invalidRule", 400),
    /** 策略名不属于平台固定策略库。 */
    UNKNOWN_STRATEGY("field-masking.unknownStrategy", 400),
    /** 非空输入执行后未改变，禁止按成功处理。 */
    UNCHANGED("field-masking.unchanged", 500);

    private final String code;
    private final int status;

    MaskingErrorCode(String code, int status) {
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
