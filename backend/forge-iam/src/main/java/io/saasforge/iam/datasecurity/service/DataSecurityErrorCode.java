package io.saasforge.iam.datasecurity.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum DataSecurityErrorCode implements ErrorCode {
    RULE_NOT_FOUND("data-security.ruleNotFound", 404),
    RULE_CODE_TAKEN("data-security.ruleCodeTaken", 409),
    RULE_IN_USE("data-security.ruleInUse", 409),
    INVALID_RULE("data-security.invalidRule", 400),
    GRADE_NOT_FOUND("data-security.gradeNotFound", 404),
    FIELD_NOT_FOUND("data-security.fieldNotFound", 404),
    INVALID_REFERENCE("data-security.invalidReference", 400);

    private final String code;
    private final int status;

    DataSecurityErrorCode(String code, int status) {
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
