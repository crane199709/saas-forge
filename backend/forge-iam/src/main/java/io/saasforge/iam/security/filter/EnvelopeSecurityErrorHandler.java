package io.saasforge.iam.security.filter;

import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.web.EnvelopeErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * {@code /auth/*} 过滤链中的 401、403 输出信封。IAM 不引入面向业务服务的 forge-sdk-security（其自动配置会带入资源服务器默认过滤链与
 * Membership 明细缓存），因此在此组合：先由 Bearer 默认处理器保留 RFC 6750 的 {@code WWW-Authenticate}，再由 forge-sdk-web 的写出器写信封。
 * {@code /oauth2/*} 授权服务器链不使用本类，保持 RFC 6749 格式。
 */
public final class EnvelopeSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();
    private final EnvelopeErrorWriter writer;

    public EnvelopeSecurityErrorHandler(EnvelopeErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        bearerEntryPoint.commence(request, response, exception);
        writer.write(request, response, FrameworkErrorCode.AUTH_UNAUTHENTICATED, exception.getMessage());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        bearerAccessDenied.handle(request, response, exception);
        writer.write(request, response, FrameworkErrorCode.AUTH_FORBIDDEN, exception.getMessage());
    }
}
