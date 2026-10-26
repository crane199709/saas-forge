package io.saasforge.iam.security.filter;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.web.UserTipResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /auth/*} 过滤链中的 401、403 输出信封。IAM 不引入面向业务服务的 forge-sdk-security（其自动配置会带入资源服务器默认过滤链与
 * Membership 明细缓存），因此按其处理器的行为在此实现：先由 Bearer 默认处理器保留 RFC 6750 的 {@code WWW-Authenticate}，再写信封。
 * {@code /oauth2/*} 授权服务器链不使用本类，保持 RFC 6749 格式。
 */
public final class EnvelopeSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    // 与 forge-sdk-web 一致：请求未带 Accept-Language 时用 zh-CN
    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();
    private final UserTipResolver userTips;
    private final ObjectProvider<Tracer> tracer;

    public EnvelopeSecurityErrorHandler(UserTipResolver userTips, ObjectProvider<Tracer> tracer) {
        this.userTips = userTips;
        this.tracer = tracer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        bearerEntryPoint.commence(request, response, exception);
        write(request, response, FrameworkErrorCode.AUTH_UNAUTHENTICATED, exception.getMessage());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        bearerAccessDenied.handle(request, response, exception);
        write(request, response, FrameworkErrorCode.AUTH_FORBIDDEN, exception.getMessage());
    }

    private void write(
            HttpServletRequest request, HttpServletResponse response, FrameworkErrorCode code, String errorMessage)
            throws IOException {
        Locale locale = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE) == null ? DEFAULT_LOCALE : request.getLocale();
        ApiResponse<Void> body = ApiResponse.error(
                        code.httpStatus(), code.code(), errorMessage, userTips.resolve(code.code(), null, locale), null)
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
