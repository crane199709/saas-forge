package io.saas.forge.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 路由函数只匹配合法方法；该守卫将其余请求转换为公开契约承诺的错误响应。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
class OpenApiRouteGuard extends OncePerRequestFilter {

    private static final Set<String> PASSWORD_SETUP_PAGE_RESOURCES = Set.of(
            "/password-setup", "/password-setup/app.js", "/password-setup/styles.css");

    private final GatewayRouteCatalog catalog;
    private final GatewayProblemDetailsWriter problemDetailsWriter;

    OpenApiRouteGuard(GatewayRouteCatalog catalog, GatewayProblemDetailsWriter problemDetailsWriter) {
        this.catalog = catalog;
        this.problemDetailsWriter = problemDetailsWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && PASSWORD_SETUP_PAGE_RESOURCES.contains(requestPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        List<GatewayRouteCatalog.Route> routes = catalog.matching(requestPath(request));
        if (routes.isEmpty()) {
            writeProblem(request, response, HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "The requested route is not declared.");
            return;
        }
        if (routes.stream().noneMatch(route -> route.method().matches(request.getMethod()))) {
            writeProblem(request, response, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                    "The requested method is not declared for this route.");
            response.setHeader("Allow", routes.stream().map(route -> route.method().name()).sorted()
                    .reduce((left, right) -> left + ", " + right).orElseThrow());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String requestPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        return request.getRequestURI().substring(contextPath.length());
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
            String detail)
            throws IOException {
        problemDetailsWriter.write(request, response, status, code, detail);
    }
}
