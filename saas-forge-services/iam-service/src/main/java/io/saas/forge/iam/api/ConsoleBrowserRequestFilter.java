package io.saas.forge.iam.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "security.browser.console-enabled", havingValue = "true")
public final class ConsoleBrowserRequestFilter extends OncePerRequestFilter {
    private final String origin;
    private final ObjectMapper json;

    public ConsoleBrowserRequestFilter(@Value("${browser.rootDomain}") String root, ObjectMapper json) {
        new BrowserRequestSecurity(root);
        this.origin = "https://console." + root;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/auth/") && !Set.of("/api/v1/auth/logout", "/api/v1/auth/password-setups").contains(path)) {
            reject(request, response, 410, "AUTH_PROTOCOL_RETIRED"); return;
        }
        if (!path.startsWith("/api/v2/auth/")) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        boolean mutation = !request.getMethod().equals("GET");
        if (!Collections.list(request.getHeaders("Origin")).equals(java.util.List.of(origin))
                || !Set.of("same-site", "same-origin").contains(String.valueOf(request.getHeader("Sec-Fetch-Site")))
                || mutation && (!Collections.list(request.getHeaders("X-SF-CSRF")).equals(java.util.List.of("1")) || !json(request))) {
            reject(request, response, 403, "BROWSER_REQUEST_REJECTED"); return;
        }
        if (mutation && !Set.of("/api/v2/auth/bootstrap", "/api/v2/auth/password-setups").contains(path)) {
            var revisions = Collections.list(request.getHeaders("If-Match"));
            if (revisions.isEmpty()) { reject(request, response, 428, "SESSION_REVISION_REQUIRED"); return; }
            if (revisions.size() != 1 || !revisions.get(0).matches("\"(0|[1-9][0-9]*)\"")) {
                reject(request, response, 400, "VALIDATION_FAILED"); return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean json(HttpServletRequest request) {
        try { return request.getContentType() != null && MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType())); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        json.writeValue(response.getOutputStream(), ConsoleAuthenticationExceptionHandler.problem(status, code, request));
    }
}
