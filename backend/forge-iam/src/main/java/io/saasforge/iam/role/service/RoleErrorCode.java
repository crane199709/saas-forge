package io.saasforge.iam.role.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum RoleErrorCode implements ErrorCode {
    /** 角色不存在、已删除或属于其他租户。 */
    NOT_FOUND("role.notFound", 404),
    /** 本租户已有同编码的角色，或编码为预置角色的保留编码。 */
    CODE_TAKEN("role.codeTaken", 409),
    /** 预置角色（Owner、Tenant Admin）不能修改授权、改名或删除。 */
    BUILTIN("role.builtin", 409),
    /** 角色仍有成员持有，不能删除。 */
    IN_USE("role.inUse", 409),
    /** 权限码不存在或已失效，不能授予。 */
    INVALID_PERMISSION("role.invalidPermission", 400),
    /** 分配的角色不存在、属于其他租户，或是只能经转让变更的 Owner。 */
    INVALID_ASSIGNMENT("role.invalidAssignment", 400),
    /** 成员不存在或不属于当前租户。 */
    MEMBERSHIP_NOT_FOUND("role.membershipNotFound", 404);

    private final String code;
    private final int httpStatus;

    RoleErrorCode(String code, int httpStatus) {
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
