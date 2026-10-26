package io.saasforge.sdk.web;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.core.api.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 在信封写出前填入当前 traceId，并让 HTTP 状态码与信封的 {@code httpStatus} 一致。不自动包装非信封的返回值（ADR-0015）。
 */
@RestControllerAdvice
class EnvelopeResponseAdvice implements ResponseBodyAdvice<Object> {

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
        return envelope.withTraceId(currentTraceId());
    }

    private String currentTraceId() {
        Tracer current = tracer.getIfAvailable();
        if (current == null) {
            return null;
        }
        TraceContext context = current.currentTraceContext().context();
        return context == null ? null : context.traceId();
    }
}
