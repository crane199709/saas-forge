package io.saasforge.sdk.security;

import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 未认证、Token 缺失、无效或已失效时输出 401 {@code auth.unauthenticated} 信封。保留 RFC 6750 的 {@code WWW-Authenticate}
 * 响应头，只把空响应体换成信封。应用自定义过滤链时，把本 Bean 同时配置到 {@code exceptionHandling} 与 {@code oauth2ResourceServer}。
 */
public final class EnvelopeAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
    private final SecurityErrorWriter writer;

    /**
     * @param tracer 用于填入 traceId；未启用 tracing 时信封不输出 traceId
     */
    public EnvelopeAuthenticationEntryPoint(ObjectProvider<Tracer> tracer) {
        this.writer = new SecurityErrorWriter(tracer);
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        bearer.commence(request, response, authException);
        writer.write(request, response, FrameworkErrorCode.AUTH_UNAUTHENTICATED, authException.getMessage());
    }
}
