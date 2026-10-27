package io.saasforge.sdk.core.api;

/**
 * 错误码：点分字符串 {@code <领域>.<原因>} 与固定的 HTTP 状态码。
 *
 * <p>每个功能包在 {@code service} 层用枚举实现，如 {@code OrderErrorCode.NOT_FOUND("order.notFound", 404)}；同一 code
 * 只在一个枚举中定义。code 同时是 i18n key，业务错误码的文案放在服务的 {@code messages*.properties}。
 */
public interface ErrorCode {

    /**
     * 错误码。
     *
     * @return 点分错误码，领域小写、原因 lowerCamelCase，如 {@code order.notFound}
     */
    String code();

    /**
     * 该错误对应的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    int httpStatus();
}
