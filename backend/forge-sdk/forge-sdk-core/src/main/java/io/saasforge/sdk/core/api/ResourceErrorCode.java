package io.saasforge.sdk.core.api;

/** 资源恢复的共享错误码；具体恢复规则由各资源实现，SDK 不执行恢复。 */
public enum ResourceErrorCode implements ErrorCode {
    /** 已删除资源的名称或编码被现有记录占用。 */
    RESTORE_CONFLICT("resource.restoreConflict", 409),
    /** 恢复所依赖的关联资源仍处于已删除状态。 */
    RESTORE_BLOCKED("resource.restoreBlocked", 409);

    private final String code;
    private final int httpStatus;

    ResourceErrorCode(String code, int httpStatus) {
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
