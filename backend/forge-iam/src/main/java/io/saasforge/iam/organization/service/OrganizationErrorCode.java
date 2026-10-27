package io.saasforge.iam.organization.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum OrganizationErrorCode implements ErrorCode {
    /** 部门不存在、已删除或属于其他租户。 */
    DEPARTMENT_NOT_FOUND("organization.departmentNotFound", 404),
    /** 目标上级是部门自身或其下级，移动后会形成环。 */
    INVALID_DEPARTMENT_MOVE("organization.invalidDepartmentMove", 400),
    /** 部门下还有子部门或成员，不能删除。 */
    DEPARTMENT_NOT_EMPTY("organization.departmentNotEmpty", 409),
    /** 岗位不存在、已删除或属于其他租户。 */
    POSITION_NOT_FOUND("organization.positionNotFound", 404),
    /** 岗位仍有成员担任，不能删除。 */
    POSITION_IN_USE("organization.positionInUse", 409),
    /** 扩展属性定义不存在、已删除或属于其他租户。 */
    ATTRIBUTE_NOT_FOUND("organization.attributeNotFound", 404),
    /** 本租户已有同编码的扩展属性。 */
    ATTRIBUTE_CODE_TAKEN("organization.attributeCodeTaken", 409),
    /** 扩展属性值未定义、类型不符或缺少必填项。 */
    INVALID_ATTRIBUTE_VALUE("organization.invalidAttributeValue", 400),
    /** 成员不存在或不属于当前租户。 */
    MEMBERSHIP_NOT_FOUND("organization.membershipNotFound", 404),
    /** 部门分配不完整：有兼任部门却没有主部门，或主部门同时出现在兼任部门中。 */
    INVALID_ASSIGNMENT("organization.invalidAssignment", 400);

    private final String code;
    private final int httpStatus;

    OrganizationErrorCode(String code, int httpStatus) {
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
