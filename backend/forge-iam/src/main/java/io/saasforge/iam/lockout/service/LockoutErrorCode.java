package io.saasforge.iam.lockout.service;

import io.saasforge.sdk.core.api.ErrorCode;

public enum LockoutErrorCode implements ErrorCode {
    /**
     * 登录标识、Identity、Operator 或客户端 IP 因失败次数过多被临时锁定；文案参数与 {@code Retry-After} 响应头为剩余秒数。不存在的标识同样计数，
     * 锁定提示不泄露账号是否存在。
     */
    LOCKED("auth.locked", 429);

    private final String code;
    private final int httpStatus;

    LockoutErrorCode(String code, int httpStatus) {
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
