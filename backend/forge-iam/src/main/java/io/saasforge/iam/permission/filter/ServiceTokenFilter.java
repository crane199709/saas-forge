package io.saasforge.iam.permission.filter;

import io.saasforge.iam.servicecredential.service.ServiceCredentialAuthentication;
import io.saasforge.iam.servicecredential.service.ServiceCredentialErrorCode;
import io.saasforge.iam.servicecredential.service.ServiceCredentialFailureAudit;
import io.saasforge.sdk.web.EnvelopeErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/** 服务间内部接口的凭证认证，数据库不可用时拒绝请求并返回 503。 */
public final class ServiceTokenFilter extends OncePerRequestFilter {
    private static final String BEARER = "Bearer ";
    private final ServiceCredentialAuthentication credentials;
    private final EnvelopeErrorWriter errors;
    private final ServiceCredentialFailureAudit failures;

    public ServiceTokenFilter(
            ServiceCredentialAuthentication credentials,
            EnvelopeErrorWriter errors,
            ServiceCredentialFailureAudit failures) {
        this.credentials = credentials;
        this.errors = errors;
        this.failures = failures;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length());
            // 兼容引导令牌；空值、控制字符、空白和超长令牌在进入数据库前拒绝。
            if (!token.isEmpty() && token.length() <= 4096 && token.chars().allMatch(c -> c > 32 && c < 127)) {
                String service;
                try {
                    service = credentials.authenticate(token, request.getRemoteAddr());
                } catch (DataAccessException | TransactionException e) {
                    errors.write(request, response, ServiceCredentialErrorCode.UNAVAILABLE, "Internal error");
                    return;
                }
                if (service != null) {
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(service, null, List.of()));
                    SecurityContextHolder.setContext(context);
                }
            } else {
                failures.record("unknown", request.getRemoteAddr(), null);
            }
        } else {
            failures.record("unknown", request.getRemoteAddr(), null);
        }
        chain.doFilter(request, response);
    }
}
