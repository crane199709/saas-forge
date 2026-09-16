package io.saas.forge.iam.api;

import io.saas.forge.iam.application.authentication.*;
import java.util.Locale;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ConsoleAuthenticationController.class)
public final class ConsoleAuthenticationExceptionHandler {
    @ExceptionHandler(ConsoleSessionException.class)
    ResponseEntity<ConsoleProblem> console(ConsoleSessionException error, HttpServletRequest request) {
        int status = switch (error.code()) {
            case SESSION_INVALID -> 401;
            case INITIAL_CREDENTIAL_RESTRICTED, CURRENT_CONTEXT_REVOKED, TARGET_CONTEXT_UNAVAILABLE -> 403;
            case SESSION_REVISION_REQUIRED -> 428;
            case SESSION_REVISION_CHANGED -> 412;
            case CONTEXTS_UNAVAILABLE, SESSION_TRANSITION_PENDING, SESSION_SECURITY_UNAVAILABLE -> 503;
            case VALIDATION_FAILED -> 400;
            default -> 409;
        };
        return response(status, error.code().name(), request);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<ConsoleProblem> credentials(AuthenticationFailedException error, HttpServletRequest request) {
        return response(401, "AUTHENTICATION_FAILED", request);
    }

    @ExceptionHandler(RefreshRotationInProgressException.class)
    ResponseEntity<ConsoleProblem> rotating(RefreshRotationInProgressException error, HttpServletRequest request) {
        return response(409, "REFRESH_ROTATION_IN_PROGRESS", request);
    }

    @ExceptionHandler({TenantAccessUnavailableException.class, AccessibleMembershipLimitExceededException.class})
    ResponseEntity<ConsoleProblem> contexts(RuntimeException error, HttpServletRequest request) { return response(503, "CONTEXTS_UNAVAILABLE", request); }

    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            jakarta.validation.ConstraintViolationException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            IllegalArgumentException.class})
    ResponseEntity<ConsoleProblem> validation(Exception error, HttpServletRequest request) { return response(400, "VALIDATION_FAILED", request); }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ConsoleProblem> unavailable(RuntimeException error, HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Console authentication unavailable: {}", error.getClass().getName());
        return response(503, "SESSION_SECURITY_UNAVAILABLE", request);
    }

    private static ResponseEntity<ConsoleProblem> response(int status, String code, HttpServletRequest request) {
        var builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == 503 || code.equals("REFRESH_ROTATION_IN_PROGRESS")) builder.header(HttpHeaders.RETRY_AFTER, "1");
        return builder.body(problem(status, code, request));
    }

    static ConsoleProblem problem(int status, String code, HttpServletRequest request) {
        return new ConsoleProblem("urn:saas.forge:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                "Console authentication request rejected", status, code,
                "The Console session request could not be completed.", AuthenticationExceptionHandler.traceId(request));
    }

    record ConsoleProblem(String type, String title, int status, String code, String detail, String traceId) { }
}
