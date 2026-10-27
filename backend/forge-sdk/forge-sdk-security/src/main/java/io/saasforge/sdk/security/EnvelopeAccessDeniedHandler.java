package io.saasforge.sdk.security;

import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 已认证但无权限时输出 403 {@code auth.forbidden} 信封，覆盖 URL 授权与方法安全两种拒绝。保留 RFC 6750 的
 * {@code WWW-Authenticate} 响应头。应用自定义过滤链时，把本 Bean 同时配置到 {@code exceptionHandling} 与 {@code oauth2ResourceServer}。
 */
public final class EnvelopeAccessDeniedHandler implements AccessDeniedHandler {

    private final BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
    private final SecurityErrorWriter writer;

    /**
     * @param tracer 用于填入 traceId；未启用 tracing 时信封不输出 traceId
     */
    public EnvelopeAccessDeniedHandler(ObjectProvider<Tracer> tracer) {
        this.writer = new SecurityErrorWriter(tracer);
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        bearer.handle(request, response, accessDeniedException);
        writer.write(request, response, FrameworkErrorCode.AUTH_FORBIDDEN, accessDeniedException.getMessage());
    }
}
