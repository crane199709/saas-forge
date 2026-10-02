package io.saas.forge.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在路由守卫前建立 Trace Context，使 Gateway 生成的错误也能关联到下游请求。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class TraceContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        request.setAttribute(TraceContext.REQUEST_ATTRIBUTE, TraceContext.establish(request));
        filterChain.doFilter(request, response);
    }
}
