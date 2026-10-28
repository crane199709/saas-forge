package io.saasforge.iam.member.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum MemberErrorCode implements ErrorCode {
    /** 成员不存在或不属于当前租户。 */
    NOT_FOUND("member.notFound", 404),
    /** 被邀请人已是本租户正常或停用的成员。 */
    ALREADY_MEMBER("member.alreadyMember", 409),
    /** 成员当前状态不允许该操作，如恢复一个未停用的成员。 */
    ILLEGAL_STATUS("member.illegalStatus", 409),
    /** Owner 不能被停用、移除，也不能主动退出，须先转让。 */
    OWNER_PROTECTED("member.ownerProtected", 409),
    /** 只有 Owner 可以转让所有者身份。 */
    NOT_OWNER("member.notOwner", 403),
    /** 转让对象须是本租户另一个正常状态的成员。 */
    INVALID_TRANSFER_TARGET("member.invalidTransferTarget", 400);

    private final String code;
    private final int httpStatus;

    MemberErrorCode(String code, int httpStatus) {
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
