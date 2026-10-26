package io.saasforge.sdk.security;

import io.saasforge.sdk.core.CurrentTenant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 把已验签 Token 中的 Active Tenant 绑定到请求线程，供租户隔离使用。
 *
 * <p>必须位于 Bearer Token 认证之后：租户只取自验签通过的 JWT，不接受客户端自称的租户请求头。
 */
public final class CurrentTenantFilter extends OncePerRequestFilter {
    /** 已认证请求在整个过滤链执行期间绑定租户，结束后清除，避免容器线程复用时残留。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken)) {
            chain.doFilter(request, response);
            return;
        }
        try (CurrentTenant.Binding ignored =
                CurrentTenant.bind(TenantContext.current().tenantId())) {
            chain.doFilter(request, response);
        }
    }
}
