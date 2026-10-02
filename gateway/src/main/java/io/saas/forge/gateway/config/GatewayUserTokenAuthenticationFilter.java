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

/** OpenAPI 声明的 UserBearerAuth 是 Gateway 是否校验 User Access Token 的唯一依据。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class GatewayUserTokenAuthenticationFilter extends OncePerRequestFilter {

    private final GatewayRouteCatalog catalog;
    private final GatewayUserTokenVerifier verifier;
    private final GatewayProblemDetailsWriter problems;

    GatewayUserTokenAuthenticationFilter(
            GatewayRouteCatalog catalog,
            GatewayUserTokenVerifier verifier,
            GatewayProblemDetailsWriter problems) {
        this.catalog = catalog;
        this.verifier = verifier;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        List<GatewayRouteCatalog.Route> routes = catalog.matching(requestPath(request));
        if (routes.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        GatewayRouteCatalog.Route matchingRoute = routes.stream()
                .filter(route -> route.method().matches(request.getMethod()))
                .findFirst()
                .orElse(null);
        if (matchingRoute == null) {
            filterChain.doFilter(request, response);
            return;
        }
        HttpRouteCatalog.CredentialRequirement requirement = matchingRoute.credentialRequirement();
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if ((requirement != HttpRouteCatalog.CredentialRequirement.USER_REQUIRED
                && requirement != HttpRouteCatalog.CredentialRequirement.USER_OPTIONAL)
                || requirement == HttpRouteCatalog.CredentialRequirement.USER_OPTIONAL && authorization == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            verifier.verify(authorization);
            filterChain.doFilter(request, response);
        } catch (GatewayTokenRevocationStatusUnavailableException exception) {
            problems.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "TOKEN_REVOCATION_STATUS_UNAVAILABLE",
                    "The User Token revocation status is unavailable.");
        } catch (GatewayUserTokenInvalidException exception) {
            if (requirement == HttpRouteCatalog.CredentialRequirement.USER_OPTIONAL) {
                // logout 必须继续到 IAM 清理 Refresh Cookie；IAM 只在 Bearer 有效时附加撤销其 jti。
                filterChain.doFilter(request, response);
                return;
            }
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            problems.write(request, response, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID",
                    "The User Access Token is missing or invalid.");
        }
    }

    private String requestPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
