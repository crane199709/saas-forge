package io.saasforge.sdk.security;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.core.api.FrameworkMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * 在 Spring Security 过滤链中直接写出失败信封。此处位于 DispatcherServlet 之外，forge-sdk-web 的 Advice 不会介入，因此自行填 traceId 与文案。
 */
final class SecurityErrorWriter {

    // 信封字段固定且不受服务的 Jackson 定制影响，独立实例即可，避免依赖服务是否声明 JsonMapper Bean
    private static final JsonMapper JSON = JsonMapper.builder().build();

    // 与 forge-sdk-web 一致：请求未带 Accept-Language 时用 zh-CN，不随部署机器的 locale 变化
    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    private final ObjectProvider<Tracer> tracer;

    SecurityErrorWriter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    void write(HttpServletRequest request, HttpServletResponse response, FrameworkErrorCode code, String errorMessage)
            throws IOException {
        Locale locale = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE) == null ? DEFAULT_LOCALE : request.getLocale();
        String userTip =
                FrameworkMessages.find(code.code(), locale).orElseGet(() -> FrameworkMessages.fallback(locale));
        ApiResponse<Void> body = ApiResponse.error(code.httpStatus(), code.code(), errorMessage, userTip, null)
                .withTraceId(currentTraceId());
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        JSON.writeValue(response.getOutputStream(), body);
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
