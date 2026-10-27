package io.saasforge.iam.session.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /auth/*} 的响应携带 Token、Membership 等凭证相关数据，一律禁止缓存，成功与错误信封都一样。
 *
 * <p>在安全过滤链之前设置：Spring Security 的默认缓存头只在响应尚无 {@code Cache-Control} 时写入，安全处理器与 {@code /error} 写出的
 * 错误信封也就保留同一个值，Controller 不必逐个设置。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuthNoStoreFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        chain.doFilter(request, response);
    }
}
