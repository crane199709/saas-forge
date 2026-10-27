package io.saasforge.gateway.envelope.service;

import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ErrorCode;
import io.saasforge.sdk.core.api.ErrorEnvelopes;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关自身产生的错误（安全拒绝、路由不存在、下游不可用等）以统一信封写出，并提供当前请求的 traceId。信封构造、语言解析与序列化取自
 * forge-sdk-core 的 {@link ErrorEnvelopes}，与 Servlet 服务一致。
 *
 * <p>traceId 取自 WebFlux 为本次请求创建的观测上下文，而不是线程上的当前 span：响应式链路跨线程执行，线程局部变量在这里不可靠。
 */
@Service
public class ErrorEnvelopeService {

    /**
     * 按错误码固定的状态码写出失败信封。
     *
     * @param exchange 当前请求
     * @param code 框架级错误码
     * @param errorMessage 面向开发者的说明，5xx 由调用方传入固定的 {@code Internal error}
     * @return 写出完成信号
     */
    public Mono<Void> write(ServerWebExchange exchange, ErrorCode code, String errorMessage) {
        return write(exchange, code, code.httpStatus(), errorMessage);
    }

    /**
     * 以指定状态码写出失败信封，用于未单独定义 code 的框架 4xx 保留原状态码。
     *
     * @param exchange 当前请求
     * @param code 框架级错误码
     * @param httpStatus 实际状态码
     * @param errorMessage 面向开发者的说明
     * @return 写出完成信号
     */
    public Mono<Void> write(ServerWebExchange exchange, ErrorCode code, int httpStatus, String errorMessage) {
        String acceptLanguage = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE);
        ApiResponse<Void> body = ErrorEnvelopes.framework(
                        code, httpStatus, errorMessage, ErrorEnvelopes.localeOf(acceptLanguage))
                .withTraceId(traceId(exchange));
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(httpStatus));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(ErrorEnvelopes.toJson(body));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 当前请求的 W3C trace id。
     *
     * @param exchange 当前请求
     * @return trace id，未启用 tracing 时为 null
     */
    public String traceId(ServerWebExchange exchange) {
        return ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(ErrorEnvelopes::traceId)
                .orElse(null);
    }
}
