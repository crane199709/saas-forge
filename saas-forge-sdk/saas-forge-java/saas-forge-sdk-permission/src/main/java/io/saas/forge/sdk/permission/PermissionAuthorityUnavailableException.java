package io.saas.forge.sdk.permission;

/** 权威状态无法确定，调用方必须返回可重试错误且不得继续执行业务操作。 */
public final class PermissionAuthorityUnavailableException extends RuntimeException {
    public PermissionAuthorityUnavailableException(Throwable cause) {
        super("Permission authority unavailable", cause);
    }
}
