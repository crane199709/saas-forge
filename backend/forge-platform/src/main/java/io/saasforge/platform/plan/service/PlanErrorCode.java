package io.saasforge.platform.plan.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum PlanErrorCode implements ErrorCode {
    /** Plan 不存在或已删除。 */
    NOT_FOUND("plan.notFound", 404),
    /** 编码已被另一个未删除的 Plan 使用。 */
    CODE_TAKEN("plan.codeTaken", 409),
    /** 新加入 Feature Pack 的权限码不在 iam 的当前目录中。 */
    UNKNOWN_PERMISSION("plan.unknownPermission", 400),
    /** 仍有未清理的租户使用该 Plan。 */
    IN_USE("plan.inUse", 409),
    /** iam 不可用，暂时无法读取权限码目录。 */
    CATALOG_UNAVAILABLE("plan.catalogUnavailable", 503),
    /** iam 不可用，暂时无法统计 Plan 变更对成员的影响。 */
    IMPACT_UNAVAILABLE("plan.impactUnavailable", 503);

    private final String code;
    private final int httpStatus;

    PlanErrorCode(String code, int httpStatus) {
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
