package io.saasforge.sdk.security;

import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ErrorEnvelopes;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 在 Spring Security 过滤链中直接写出失败信封。此处位于 DispatcherServlet 之外，forge-sdk-web 的 Advice 不会介入，因此自行填 traceId 与文案。
 *
 * <p>不复用 forge-sdk-web 的 {@code EnvelopeErrorWriter}：WebFlux 网关也依赖本模块，引入 forge-sdk-web 会把 Spring MVC 带进网关。
 * 信封构造、语言解析与序列化取自 forge-sdk-core 的 {@link ErrorEnvelopes}，这里只剩 Servlet 写出。
 */
final class SecurityErrorWriter {

    private final ObjectProvider<Tracer> tracer;

    SecurityErrorWriter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    void write(HttpServletRequest request, HttpServletResponse response, FrameworkErrorCode code, String errorMessage)
            throws IOException {
        ApiResponse<Void> body = ErrorEnvelopes.framework(
                        code, errorMessage, ErrorEnvelopes.localeOf(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE)))
                .withTraceId(ErrorEnvelopes.traceId(tracer.getIfAvailable()));
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(ErrorEnvelopes.toJson(body));
    }
}
