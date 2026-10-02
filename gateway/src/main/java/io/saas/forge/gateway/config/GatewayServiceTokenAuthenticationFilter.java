package io.saas.forge.gateway.config;

import io.saas.forge.contracts.route.HttpRouteCatalog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 对 Catalog 中的 SERVICE_REQUIRED operation 执行 Service Token 与 AND Scope 门禁。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
class GatewayServiceTokenAuthenticationFilter extends OncePerRequestFilter {

    private final GatewayRouteCatalog catalog;
    private final GatewayServiceTokenVerifier verifier;
    private final GatewayProblemDetailsWriter problems;

    GatewayServiceTokenAuthenticationFilter(
            GatewayRouteCatalog catalog,
            GatewayServiceTokenVerifier verifier,
            GatewayProblemDetailsWriter problems) {
        this.catalog = catalog;
        this.verifier = verifier;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        GatewayRouteCatalog.Route route = matchingRoute(request);
        if (route == null
                || route.credentialRequirement() != HttpRouteCatalog.CredentialRequirement.SERVICE_REQUIRED) {
            filterChain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        try {
            verifier.verify(authorization, route.requiredScopes());
            filterChain.doFilter(request, response);
        } catch (GatewayTokenRevocationStatusUnavailableException exception) {
            problems.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "TOKEN_REVOCATION_STATUS_UNAVAILABLE",
                    "The Service Token revocation status is unavailable.");
        } catch (GatewayServiceTokenScopeInsufficientException exception) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    "Bearer error=\"insufficient_scope\", scope=\"" + String.join(" ", route.requiredScopes()) + "\"");
            problems.write(request, response, HttpStatus.FORBIDDEN, "ACCESS_TOKEN_SCOPE_INSUFFICIENT",
                    "The Service Access Token does not grant every required scope.");
        } catch (GatewayServiceTokenInvalidException exception) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    authorization == null ? "Bearer" : "Bearer error=\"invalid_token\"");
            problems.write(request, response, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID",
                    "The Service Access Token is missing or invalid.");
        }
    }

    private GatewayRouteCatalog.Route matchingRoute(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String path = request.getRequestURI().substring(contextPath.length());
        return catalog.matching(path).stream()
                .filter(route -> route.method().matches(request.getMethod()))
                .findFirst()
                .orElse(null);
    }
}
