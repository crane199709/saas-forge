package io.saasforge.iam.verification.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum VerificationErrorCode implements ErrorCode {
    /** 验证码不存在：已过期、已使用、因错误次数过多作废，或从未发送。 */
    CODE_EXPIRED("verification.codeExpired", 400),
    /** 验证码不匹配，且尚未达到错误次数上限。 */
    CODE_INVALID("verification.codeInvalid", 400),
    /** 本次错误使次数达到上限，验证码随之作废，需重新获取。 */
    ATTEMPTS_EXCEEDED("verification.attemptsExceeded", 400),
    /** 同一目标在发送间隔内再次请求验证码；参数为剩余秒数。 */
    TOO_FREQUENT("verification.tooFrequent", 429);

    private final String code;
    private final int httpStatus;

    VerificationErrorCode(String code, int httpStatus) {
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
