package io.saasforge.sdk.core.api;

/** 框架级错误码（统一接口格式规格 5.2），由 SDK 映射并提供文案，服务不重复定义。 */
public enum FrameworkErrorCode implements ErrorCode {
    /** Bean Validation 校验失败、分页或排序参数非法。 */
    REQUEST_INVALID("request.invalid", 400),
    /** JSON 格式错误、参数类型不匹配。 */
    REQUEST_MALFORMED("request.malformed", 400),
    /** 路由不存在。 */
    REQUEST_NOT_FOUND("request.notFound", 404),
    /** 请求方法不支持。 */
    REQUEST_METHOD_NOT_ALLOWED("request.methodNotAllowed", 405),
    /** 未认证、Token 无效或已失效。 */
    AUTH_UNAUTHENTICATED("auth.unauthenticated", 401),
    /** 已认证但无权限。 */
    AUTH_FORBIDDEN("auth.forbidden", 403),
    /** 网关下游不可用或超时、依赖的 Redis 不可用。 */
    SYSTEM_UNAVAILABLE("system.unavailable", 503),
    /** 其他未知异常。 */
    SYSTEM_ERROR("system.error", 500);

    private final String code;
    private final int httpStatus;

    FrameworkErrorCode(String code, int httpStatus) {
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
