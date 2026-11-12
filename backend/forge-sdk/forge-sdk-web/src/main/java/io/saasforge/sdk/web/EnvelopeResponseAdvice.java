package io.saasforge.sdk.web;

import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ErrorEnvelopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 在信封写出前填入当前 traceId，并让 HTTP 状态码与信封的 {@code httpStatus} 一致。不自动包装非信封的返回值（ADR-0015）。
 */
@RestControllerAdvice
@Order(0)
class EnvelopeResponseAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeResponseAdvice.class);

    private final ObjectProvider<Tracer> tracer;

    EnvelopeResponseAdvice(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (!(body instanceof ApiResponse<?> envelope)) {
            return body;
        }
        response.setStatusCode(HttpStatus.valueOf(envelope.httpStatus()));
        String traceId = ErrorEnvelopes.traceId(tracer.getIfAvailable());
        if (traceId == null && request instanceof ServletServerHttpRequest servlet) {
            // 容器转发到 /error 时请求的观测 scope 已关闭，线程上没有当前 span，改从请求属性中的观测上下文取
            traceId = ServerHttpObservationFilter.findObservationContext(servlet.getServletRequest())
                    .map(ErrorEnvelopes::traceId)
                    .orElse(null);
        }
        ApiResponse<?> result = envelope.withTraceId(traceId);
        if (request instanceof ServletServerHttpRequest servlet) {
            ServletFailureLogs.write(
                    log,
                    servlet.getServletRequest(),
                    result,
                    result.httpStatus() == 503 ? Level.WARN : Level.ERROR,
                    null);
        }
        return result;
    }
}
