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
    /** 权限码有效但不在租户 Plan 的 Feature Pack 中，不能授予。 */
    NOT_IN_PLAN("role.permissionNotInPlan", 400),
    /** 分配的角色不存在、属于其他租户，或是只能经转让变更的 Owner。 */
    INVALID_ASSIGNMENT("role.invalidAssignment", 400),
    /** 成员不存在或不属于当前租户。 */
    MEMBERSHIP_NOT_FOUND("role.membershipNotFound", 404),
    /** Data Resource 不存在、重复，或不支持所选的 Data Scope（如未声明归属部门却选「本部门」）。 */
    INVALID_DATA_POLICY("role.invalidDataPolicy", 400),
    /** 自定义条件不是合法的表达式树：结构错误、属性未声明、操作符不适用于属性类型或取值类型不符。 */
    INVALID_DATA_CONDITION("role.invalidDataCondition", 400),
    /** 预览的样例数据含未声明的属性、取值与属性类型不符，或条数超出上限。 */
    INVALID_PREVIEW_SAMPLE("role.invalidPreviewSample", 400);

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
