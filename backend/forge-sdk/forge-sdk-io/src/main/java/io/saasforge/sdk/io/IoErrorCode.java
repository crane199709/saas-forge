package io.saasforge.sdk.io;

import io.saasforge.sdk.core.api.ErrorCode;

/** 导入导出的业务错误。 */
public enum IoErrorCode implements ErrorCode {
    /** 本实例的执行槽与等待队列均已占满。 */
    BUSY("io.busy", 429),
    /** 任务不存在或不属于提交人。 */
    NOT_FOUND("io.notFound", 404),
    /** 尚无结果，或文件已过期。 */
    RESULT_UNAVAILABLE("io.resultUnavailable", 409),
    /** 不支持的 Excel 文件或模型。 */
    INVALID_FILE("io.invalidFile", 400),
    /** 超过单次行数上限。 */
    ROW_LIMIT("io.rowLimit", 400),
    /** 至少一行校验失败，未写入业务数据。 */
    VALIDATION_FAILED("io.validationFailed", 400),
    /** 实例中断或任务超时。 */
    INTERRUPTED("io.interrupted", 500),
    /** 执行异常。 */
    FAILED("io.failed", 500);
    private final String code;
    private final int status;

    IoErrorCode(String code, int status) {
        this.code = code;
        this.status = status;
    }
    /** 国际化键。 */
    public String code() {
        return code;
    }
    /** HTTP 状态。 */
    public int httpStatus() {
        return status;
    }
}
