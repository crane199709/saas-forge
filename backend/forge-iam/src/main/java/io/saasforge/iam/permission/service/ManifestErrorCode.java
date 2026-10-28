package io.saasforge.iam.permission.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum ManifestErrorCode implements ErrorCode {
    /** 清单内容不完整或格式非法，如版本为空、菜单引用了清单中没有的权限码。 */
    INVALID("manifest.invalid", 400),
    /** 上报令牌所属的服务与清单声明的服务不一致：服务只能上报自己的清单。 */
    SERVICE_MISMATCH("manifest.serviceMismatch", 403),
    /** 同一版本已上报过不同内容；清单版本不可变，改动须升级版本。 */
    VERSION_CONFLICT("manifest.versionConflict", 409),
    /** 权限码已归属其他服务；权限码全平台唯一。 */
    PERMISSION_CONFLICT("manifest.permissionConflict", 409),
    /** Data Resource 编码已由其他服务声明；资源编码全平台唯一，是 Data Policy 与权限明细的键。 */
    DATA_RESOURCE_CONFLICT("manifest.dataResourceConflict", 409);

    private final String code;
    private final int httpStatus;

    ManifestErrorCode(String code, int httpStatus) {
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
