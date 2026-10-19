package io.saas.forge.gateway.config;

import io.saas.forge.sdk.permission.HttpPermissionChecker;
import io.saas.forge.sdk.permission.PermissionAuthorityUnavailableException;
import io.saas.forge.sdk.permission.PermissionDeniedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Gateway 在转发 Example 业务请求之前逐次读取 Tenant Access 权威判定。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 7)
final class GatewayPermissionFilter extends OncePerRequestFilter {
    private static final Map<String, String> OPERATIONS = Map.of(
            "createProject", "example:project:create",
            "listProjects", "example:project:list",
            "getProject", "example:project:read",
            "updateProject", "example:project:update",
            "deleteProject", "example:project:delete",
            "createTask", "example:task:create",
            "listTasks", "example:task:list",
            "getTask", "example:task:read",
            "updateTask", "example:task:update",
            "deleteTask", "example:task:delete");

    private final GatewayRouteCatalog catalog;
    private final GatewayProblemDetailsWriter problems;
    private final HttpPermissionChecker checker;

    GatewayPermissionFilter(GatewayRouteCatalog catalog, GatewayProblemDetailsWriter problems,
            LoadBalancerClient discovery) {
        this.catalog = catalog;
        this.problems = problems;
        this.checker = new HttpPermissionChecker(() -> {
            var instance = discovery.choose("tenant-access-service");
            return instance == null ? null : instance.getUri();
        });
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var route = catalog.matching(request.getRequestURI().substring(request.getContextPath().length()))
                .stream().filter(candidate -> candidate.method().matches(request.getMethod())).findFirst().orElse(null);
        if (route == null || !"project-service".equals(route.serviceId())) {
            chain.doFilter(request, response);
            return;
        }
        String code = OPERATIONS.get(route.operationId());
        if (code == null) {
            problems.write(request, response, HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
                    "The business operation has no registered Permission.");
            return;
        }
        try {
            checker.require(code, request.getHeader(HttpHeaders.AUTHORIZATION));
            chain.doFilter(request, response);
        } catch (PermissionDeniedException denied) {
            problems.write(request, response, HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
                    "The current Membership lacks this Permission.");
        } catch (PermissionAuthorityUnavailableException unavailable) {
            problems.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "PERMISSION_AUTHORITY_UNAVAILABLE",
                    "Permission authority is temporarily unavailable.");
        }
    }
}
