package io.saas.forge.sdk.permission;

/** 使用当前请求的 User Access Token 同步查询权威 Permission。 */
@FunctionalInterface
public interface PermissionChecker {
    void require(String code, String authorization);
}
