package io.saasforge.gateway.envelope.filter;

import io.saasforge.gateway.envelope.service.ErrorEnvelopeService;
import io.saasforge.gateway.logcontext.service.RequestLogContext;
import io.saasforge.sdk.core.api.ErrorEnvelopes;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 取代 WebFlux 默认的错误格式，网关自身产生的错误一律输出信封（统一接口格式规格 5.2）。只处理网关内抛出的异常，下游已返回的响应原样透传，不重复包装。
 *
 * <p>排在 Boot 默认处理器（order -1）之前。下游不可用、超时与 Redis 不可用统一为 503：前端据此提示稍后重试，而不是重新登录。
 */
@Component
@Order(-2)
class EnvelopeErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeErrorWebExceptionHandler.class);

    private final ErrorEnvelopeService envelopes;
    private final RequestLogContext logContext;

    EnvelopeErrorWebExceptionHandler(ErrorEnvelopeService envelopes, RequestLogContext logContext) {
        this.envelopes = envelopes;
        this.logContext = logContext;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        int status =
                ex instanceof ErrorResponse response ? response.getStatusCode().value() : 500;
        if (status == HttpStatus.NOT_FOUND.value()) {
            return envelopes.write(exchange, FrameworkErrorCode.REQUEST_NOT_FOUND, "No route for " + route(exchange));
        }
        if (status == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return envelopes.write(exchange, FrameworkErrorCode.REQUEST_METHOD_NOT_ALLOWED, "Method not allowed");
        }
        if (status >= 400 && status < 500) {
            // 与 forge-sdk-web 一致：未单独定义 code 的框架 4xx 保留实际状态码
            return envelopes.write(
                    exchange,
                    FrameworkErrorCode.REQUEST_MALFORMED,
                    status,
                    ex.getClass().getSimpleName());
        }
        if (isUnavailable(ex, status)) {
            // 异常处理在过滤链之外，链内写入 Reactor context 的租户读不到，需显式绑定
            try (RequestLogContext.Binding ignored = logContext.bind(exchange)) {
                log.warn("Upstream unavailable for {}", route(exchange), ex);
            }
            return envelopes.write(exchange, FrameworkErrorCode.SYSTEM_UNAVAILABLE, ErrorEnvelopes.INTERNAL_ERROR);
        }
        try (RequestLogContext.Binding ignored = logContext.bind(exchange)) {
            log.error("Unhandled gateway error for {}", route(exchange), ex);
        }
        return envelopes.write(exchange, FrameworkErrorCode.SYSTEM_ERROR, ErrorEnvelopes.INTERNAL_ERROR);
    }

    private static boolean isUnavailable(Throwable ex, int status) {
        if (status == HttpStatus.BAD_GATEWAY.value()
                || status == HttpStatus.SERVICE_UNAVAILABLE.value()
                || status == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return true;
        }
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof TimeoutException
                    || cause instanceof DataAccessResourceFailureException) {
                return true;
            }
        }
        return false;
    }

    private static String route(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() + " " + exchange.getRequest().getPath();
    }
}
