package io.saas.forge.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 只透传可验证的领域 Problem Details，避免将上游实现细节变成公共 API 契约。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class GatewayErrorNormalizationFilter extends OncePerRequestFilter {

    private static final Pattern PROBLEM_CODE = Pattern.compile("^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$");

    private final ObjectMapper objectMapper;

    private final GatewayProblemDetailsWriter problemDetailsWriter;

    GatewayErrorNormalizationFilter(ObjectMapper objectMapper, GatewayProblemDetailsWriter problemDetailsWriter) {
        this.objectMapper = objectMapper;
        this.problemDetailsWriter = problemDetailsWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, cachedResponse);
        } catch (ServletException exception) {
            if (!hasUpstreamFailureCause(exception)) {
                throw exception;
            }
            cachedResponse.reset();
            writeUpstreamProblem(request, cachedResponse, exception);
        } catch (RuntimeException exception) {
            if (!hasUpstreamFailureCause(exception)) {
                throw exception;
            }
            cachedResponse.reset();
            writeUpstreamProblem(request, cachedResponse, exception);
        }

        if (!isGatewayProblem(request) && cachedResponse.getStatus() >= HttpStatus.BAD_REQUEST.value()
                && !isEligibleDownstreamProblem(request, cachedResponse)) {
            cachedResponse.reset();
            problemDetailsWriter.write(request, cachedResponse, HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID_RESPONSE",
                    "The upstream service returned an invalid error response.");
        }
        cachedResponse.copyBodyToResponse();
    }

    private void writeUpstreamProblem(HttpServletRequest request, HttpServletResponse response, Throwable exception)
            throws IOException {
        if (hasNoHealthyInstanceCause(exception)) {
            problemDetailsWriter.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE",
                    "No healthy upstream service instance is available.");
            return;
        }
        if (hasTimeoutCause(exception)) {
            problemDetailsWriter.write(request, response, HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT",
                    "The upstream service did not respond before the configured timeout.");
            return;
        }
        problemDetailsWriter.write(request, response, HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID_RESPONSE",
                "The upstream service could not provide a valid response.");
    }

    private boolean isGatewayProblem(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(GatewayProblemDetailsWriter.GENERATED_PROBLEM_ATTRIBUTE));
    }

    private boolean isEligibleDownstreamProblem(HttpServletRequest request, ContentCachingResponseWrapper response) {
        String contentType = response.getContentType();
        if (contentType == null || !MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(MediaType.parseMediaType(contentType))) {
            return false;
        }
        try {
            JsonNode problem = objectMapper.readTree(response.getContentAsByteArray());
            if (!problem.isObject() || !hasText(problem, "type") || !hasText(problem, "title")
                    || !problem.path("status").canConvertToInt() || !hasText(problem, "code")
                    || !hasText(problem, "detail") || !hasText(problem, "traceId")) {
                return false;
            }
            String code = problem.path("code").textValue();
            return PROBLEM_CODE.matcher(code).matches()
                    && response.getStatus() == problem.path("status").intValue()
                    && GatewayProblemDetailsWriter.type(code).equals(problem.path("type").textValue())
                    && TraceContext.current(request).traceId().equals(problem.path("traceId").textValue());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean hasText(JsonNode problem, String field) {
        return problem.path(field).isTextual() && !problem.path(field).textValue().isBlank();
    }

    private boolean hasTimeoutCause(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private boolean hasUpstreamFailureCause(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ResourceAccessException || isNoHealthyInstance(cause)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private boolean hasNoHealthyInstanceCause(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (isNoHealthyInstance(cause)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private boolean isNoHealthyInstance(Throwable exception) {
        return exception instanceof HttpServerErrorException serverError
                && serverError.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value();
    }
}
