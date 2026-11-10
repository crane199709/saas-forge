package io.saasforge.sdk.web;

import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ErrorCode;
import io.saasforge.sdk.core.api.ErrorEnvelopes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 在 Spring MVC 之外直接写出失败信封，供安全过滤链的入口与拒绝处理器等使用。此处 {@link EnvelopeResponseAdvice} 不会介入，因此自行填入
 * traceId 与按请求语言生成的 {@code userTip}。
 */
public class EnvelopeErrorWriter {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeErrorWriter.class);

    private final UserTipResolver userTips;
    private final ObjectProvider<Tracer> tracer;

    /**
     * @param userTips 文案解析器，服务的文案优先于 SDK 自带文案
     * @param tracer 用于填入 traceId；未启用 tracing 时信封不输出 traceId
     */
    public EnvelopeErrorWriter(UserTipResolver userTips, ObjectProvider<Tracer> tracer) {
        this.userTips = userTips;
        this.tracer = tracer;
    }

    /**
     * 按错误码固定的状态码写出失败信封，覆盖已设置的状态码与 Content-Type，保留其他响应头。
     *
     * @param request 当前请求，用于取 {@code Accept-Language}
     * @param response 尚未提交的响应
     * @param code 错误码
     * @param errorMessage 面向开发者的说明
     * @throws IOException 写出失败
     */
    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String errorMessage)
            throws IOException {
        Locale locale = ErrorEnvelopes.localeOf(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
        ApiResponse<Void> body = ApiResponse.error(
                        code.httpStatus(), code.code(), errorMessage, userTips.resolve(code.code(), null, locale), null)
                .withTraceId(ErrorEnvelopes.traceId(tracer.getIfAvailable()));
        ServletFailureLogs.write(log, request, body, code.httpStatus() == 503 ? Level.WARN : Level.ERROR, null);
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(ErrorEnvelopes.toJson(body));
    }
}
