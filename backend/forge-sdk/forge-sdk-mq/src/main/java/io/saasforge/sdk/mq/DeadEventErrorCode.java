package io.saasforge.sdk.mq;

import io.saasforge.sdk.core.api.ErrorCode;

/** Dead Event 查询和处置的业务拒绝。 */
public enum DeadEventErrorCode implements ErrorCode {
    /** 记录不存在。 */
    NOT_FOUND("deadEvent.notFound", 404),
    /** 事件已进入终态。 */
    ALREADY_HANDLED("deadEvent.alreadyHandled", 409),
    /** 必须填写处置原因。 */
    REASON_REQUIRED("deadEvent.reasonRequired", 400),
    /** Handler 或原消息无法重放。 */
    REPLAY_FAILED("deadEvent.replayFailed", 409);
    private final String code;
    private final int status;

    DeadEventErrorCode(String code, int status) {
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
