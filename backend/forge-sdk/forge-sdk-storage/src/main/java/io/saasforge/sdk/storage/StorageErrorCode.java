package io.saasforge.sdk.storage;

import io.saasforge.sdk.core.api.ErrorCode;

/** 对象存储的错误码，文案由 SDK 提供。 */
public enum StorageErrorCode implements ErrorCode {
    /** 对象不存在、尚未确认上传或属于其他租户；三者不区分，不泄露其他租户的对象是否存在。 */
    OBJECT_NOT_FOUND("storage.objectNotFound", 404),
    /** 上传后租户的已用存储将超出 Plan 的存储容量 Quota。 */
    QUOTA_EXCEEDED("storage.quotaExceeded", 409),
    /** 确认上传时存储中没有该对象，或大小与申请时不一致。 */
    UPLOAD_INCOMPLETE("storage.uploadIncomplete", 409),
    /** 文件扩展名不在用途的白名单中。 */
    EXTENSION_NOT_ALLOWED("storage.extensionNotAllowed", 400),
    /** 文件大小超过用途的上限。 */
    FILE_TOO_LARGE("storage.fileTooLarge", 400),
    /** 当前租户对象的用途与业务预期用途不同。 */
    PURPOSE_MISMATCH("storage.purposeMismatch", 400);

    private final String code;
    private final int httpStatus;

    StorageErrorCode(String code, int httpStatus) {
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
