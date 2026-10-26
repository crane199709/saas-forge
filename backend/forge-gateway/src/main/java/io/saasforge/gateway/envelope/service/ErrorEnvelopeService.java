package io.saasforge.gateway.envelope.service;

import io.micrometer.tracing.handler.TracingObservationHandler;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.core.api.FrameworkMessages;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * 网关自身产生的错误（安全拒绝、路由不存在、下游不可用等）以统一信封写出，并提供当前请求的 traceId。
 *
 * <p>traceId 取自 WebFlux 为本次请求创建的观测上下文，而不是线程上的当前 span：响应式链路跨线程执行，线程局部变量在这里不可靠。
 */
@Service
public class ErrorEnvelopeService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // 与 Servlet 服务一致：请求未带 Accept-Language 时用 zh-CN，不随部署机器的 locale 变化
    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    /**
     * 写出失败信封。
     *
     * @param exchange 当前请求
     * @param code 框架级错误码
     * @param httpStatus 实际状态码，通常等于 {@code code.httpStatus()}；未单独定义 code 的框架 4xx 保留原状态码
     * @param errorMessage 面向开发者的说明，5xx 由调用方传入固定的 {@code Internal error}
     * @return 写出完成信号
     */
    public Mono<Void> write(ServerWebExchange exchange, FrameworkErrorCode code, int httpStatus, String errorMessage) {
        Locale locale = locale(exchange);
        String userTip =
                FrameworkMessages.find(code.code(), locale).orElseGet(() -> FrameworkMessages.fallback(locale));
        ApiResponse<Void> body = ApiResponse.error(httpStatus, code.code(), errorMessage, userTip, null)
                .withTraceId(traceId(exchange));
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(httpStatus));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(JSON.writeValueAsBytes(body));
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
                .map(context -> {
                    TracingObservationHandler.TracingContext tracing =
                            context.get(TracingObservationHandler.TracingContext.class);
                    return tracing == null ? null : tracing.getSpan();
                })
                .map(span -> span.context().traceId())
                .orElse(null);
    }

    private static Locale locale(ServerWebExchange exchange) {
        List<Locale> locales = exchange.getRequest().getHeaders().getAcceptLanguageAsLocales();
        return locales.isEmpty() ? DEFAULT_LOCALE : locales.get(0);
    }
}
