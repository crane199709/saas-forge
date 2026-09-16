package io.saas.forge.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在路由到下游前独立校验受控浏览器写请求的来源与请求形态。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class BrowserRequestSecurityFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserRequestSecurityFilter.class);
    private static final String CSRF_HEADER = "X-SF-CSRF";
    private static final String FETCH_SITE_HEADER = "Sec-Fetch-Site";
    private static final Set<String> ALWAYS_PROTECTED_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/password-setups");

    private final Set<String> controlledOrigins;
    private final String consoleOrigin;
    private final GatewayProblemDetailsWriter problems;

    BrowserRequestSecurityFilter(
            @Value("${browser.rootDomain}") String rootDomain,
            GatewayProblemDetailsWriter problems) {
        this.consoleOrigin = "https://console." + rootDomain;
        this.controlledOrigins = Set.of(
                "https://platform." + rootDomain,
                "https://console." + rootDomain);
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/v2/auth/")) return HttpMethod.OPTIONS.matches(request.getMethod());
        if (HttpMethod.GET.matches(request.getMethod())
                || HttpMethod.HEAD.matches(request.getMethod())
                || HttpMethod.OPTIONS.matches(request.getMethod())
                || HttpMethod.TRACE.matches(request.getMethod())) {
            return true;
        }
        return !ALWAYS_PROTECTED_PATHS.contains(request.getRequestURI())
                && request.getHeader(HttpHeaders.ORIGIN) == null
                && request.getHeader(FETCH_SITE_HEADER) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rejection = rejectionReason(request);
        if (rejection != null) {
            LOGGER.warn("Rejected browser request method={} path={} reason={}",
                    request.getMethod(), request.getRequestURI(), rejection);
            problems.write(request, response, HttpStatus.FORBIDDEN, "BROWSER_REQUEST_REJECTED",
                    "The browser request is not from a controlled Console origin.");
            return;
        }
        chain.doFilter(request, response);
    }

    private String rejectionReason(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (request.getRequestURI().startsWith("/api/v2/auth/")) {
            if (!java.util.Collections.list(request.getHeaders(HttpHeaders.ORIGIN)).equals(java.util.List.of(consoleOrigin))) return "ORIGIN";
            if (!Set.of("same-site", "same-origin").contains(String.valueOf(request.getHeader(FETCH_SITE_HEADER)))) return "FETCH_SITE";
            if (HttpMethod.GET.matches(request.getMethod())) return null;
        }
        if (origin == null || !controlledOrigins.contains(origin)) {
            return "ORIGIN";
        }
        if (!"1".equals(request.getHeader(CSRF_HEADER))) {
            return "CSRF";
        }
        if ("cross-site".equalsIgnoreCase(request.getHeader(FETCH_SITE_HEADER))) {
            return "FETCH_SITE";
        }
        try {
            String contentType = request.getContentType();
            if (contentType == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType))) {
                return "CONTENT_TYPE";
            }
        } catch (IllegalArgumentException invalidContentType) {
            return "CONTENT_TYPE";
        }
        return null;
    }
}
