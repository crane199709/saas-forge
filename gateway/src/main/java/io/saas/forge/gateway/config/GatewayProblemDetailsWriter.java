package io.saas.forge.gateway.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Gateway 只生成基础设施错误；领域服务拥有的合格 Problem Details 不在这里重写。
 */
@Component
class GatewayProblemDetailsWriter {

    static final String GENERATED_PROBLEM_ATTRIBUTE = GatewayProblemDetailsWriter.class.getName();

    private final ObjectMapper objectMapper;

    GatewayProblemDetailsWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        request.setAttribute(GENERATED_PROBLEM_ATTRIBUTE, Boolean.TRUE);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Map.of(
                "type", type(code),
                "title", title(code),
                "status", status.value(),
                "code", code,
                "detail", detail,
                "traceId", TraceContext.current(request).traceId()));
    }

    static String type(String code) {
        return "urn:saas.forge:problem:" + code.toLowerCase().replace('_', '-');
    }

    private String title(String code) {
        return switch (code) {
            case "ROUTE_NOT_FOUND" -> "Route not found";
            case "METHOD_NOT_ALLOWED" -> "Method not allowed";
            case "UPSTREAM_INVALID_RESPONSE" -> "Upstream invalid response";
            case "UPSTREAM_TIMEOUT" -> "Upstream timeout";
            case "UPSTREAM_UNAVAILABLE" -> "Upstream service unavailable";
            case "BROWSER_REQUEST_REJECTED" -> "Browser request rejected";
            case "ACCESS_TOKEN_INVALID" -> "Access Token invalid";
            case "ACCESS_TOKEN_SCOPE_INSUFFICIENT" -> "Access Token scope insufficient";
            case "TOKEN_REVOCATION_STATUS_UNAVAILABLE" -> "Token revocation status unavailable";
            case "PERMISSION_DENIED" -> "Permission denied";
            case "PERMISSION_AUTHORITY_UNAVAILABLE" -> "Permission authority unavailable";
            default -> throw new IllegalArgumentException("Unsupported Gateway Problem Details code: " + code);
        };
    }
}
