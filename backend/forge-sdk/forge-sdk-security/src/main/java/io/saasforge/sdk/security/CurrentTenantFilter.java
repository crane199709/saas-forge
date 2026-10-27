package io.saasforge.sdk.security;

import io.saasforge.sdk.core.CurrentTenant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 把已验签 Token 中的 Active Tenant 绑定到请求线程，供租户隔离使用；同时把租户与 Membership 写入日志 MDC，业务日志无需参与即可按二者检索。
 *
 * <p>必须位于 Bearer Token 认证之后：租户只取自验签通过的 JWT，不接受客户端自称的租户请求头。
 */
public final class CurrentTenantFilter extends OncePerRequestFilter {
    /** 日志 MDC 中 Membership ID 的键；租户 ID 的键见 {@link CurrentTenant#MDC_KEY}。 */
    public static final String MEMBERSHIP_MDC_KEY = "membership_id";

    /** 已认证请求在整个过滤链执行期间绑定租户，结束后清除，避免容器线程复用时残留。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken)) {
            chain.doFilter(request, response);
            return;
        }
        TenantContext context = TenantContext.current();
        try (CurrentTenant.Binding ignored = CurrentTenant.bind(context.tenantId());
                MDC.MDCCloseable membership = MDC.putCloseable(
                        MEMBERSHIP_MDC_KEY, context.membershipId().toString())) {
            chain.doFilter(request, response);
        }
    }
}
