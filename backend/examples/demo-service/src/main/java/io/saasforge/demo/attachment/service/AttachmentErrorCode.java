package io.saasforge.demo.attachment.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum AttachmentErrorCode implements ErrorCode {
    /** 其他订单或其他租户的附件与不存在的附件同样返回本错误。 */
    NOT_FOUND("attachment.notFound", 404),
    /** 同一对象已作为附件登记。 */
    ALREADY_ATTACHED("attachment.alreadyAttached", 409);

    private final String code;
    private final int httpStatus;

    AttachmentErrorCode(String code, int httpStatus) {
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
