package io.saasforge.sdk.scheduling;

import io.saasforge.sdk.core.api.ErrorCode;

/** Task Run 运维命令的业务拒绝；平台透传同一个错误码定义。 */
public enum TaskErrorCode implements ErrorCode {
    /** 任务已经持锁运行。 */
    ALREADY_RUNNING("task.alreadyRunning", 409),
    /** 服务没有声明该任务。 */
    NOT_FOUND("task.notFound", 404),
    /** 目标租户不处于试用或正常状态。 */
    TENANT_NOT_ELIGIBLE("task.tenantNotEligible", 409);
    private final String code;
    private final int status;

    TaskErrorCode(String code, int status) {
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
