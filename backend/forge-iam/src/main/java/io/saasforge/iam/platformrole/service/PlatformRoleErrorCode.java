package io.saasforge.iam.platformrole.service;

import io.saasforge.sdk.core.api.ErrorCode;

/** 平台角色的错误码；Data Policy 的校验与租户角色共用一套规则，沿用 {@code role.invalidDataPolicy} 等错误码。 */
public enum PlatformRoleErrorCode implements ErrorCode {
    /** 平台角色不存在或已删除。 */
    NOT_FOUND("platformRole.notFound", 404),
    /** 已有同编码的平台角色，或编码为超级管理员的保留编码。 */
    CODE_TAKEN("platformRole.codeTaken", 409),
    /** 超级管理员不能修改授权、改名或删除。 */
    BUILTIN("platformRole.builtin", 409),
    /** 平台角色仍有 Operator 持有，不能删除。 */
    IN_USE("platformRole.inUse", 409),
    /** 平台权限码不存在、已失效或属于 Tenant Realm，不能授予。 */
    INVALID_PERMISSION("platformRole.invalidPermission", 400),
    /** 分配的平台角色不存在或已删除。 */
    INVALID_ASSIGNMENT("platformRole.invalidAssignment", 400),
    /** 操作后将没有状态正常的超级管理员，无人能再分配角色。 */
    LAST_SUPER_ADMIN("platformRole.lastSuperAdmin", 409);

    private final String code;
    private final int httpStatus;

    PlatformRoleErrorCode(String code, int httpStatus) {
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
