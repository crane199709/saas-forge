/**
 * 响应信封与错误模型（ADR-0015）。纯 Java 类型，不依赖 Spring，Servlet 服务与 WebFlux 网关共用。
 *
 * <p>Controller 显式返回 {@link io.saasforge.sdk.core.api.ApiResponse}；业务错误抛 {@link
 * io.saasforge.sdk.core.api.ForgeException}，由各功能包的 {@link io.saasforge.sdk.core.api.ErrorCode} 枚举固定错误码与 HTTP
 * 状态码。
 */
package io.saasforge.sdk.core.api;
