package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.auth.PlatformAuthorizationDeniedException;
import io.saas.forge.tenantaccess.application.tenant.IdempotencyKeyInvalidException;
import io.saas.forge.tenantaccess.application.tenant.IdempotencyKeyReusedException;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleException;
import io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupException;
import io.saas.forge.tenantaccess.application.administrator.TenantAdministratorInitializationException;
import io.saas.forge.tenantaccess.domain.tenant.TenantExpiryInvalidException;
import io.saas.forge.tenantaccess.domain.tenant.TenantStateTransitionNotAllowedException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TenantCreationController.class)
public class TenantCreationExceptionHandler {
    @ExceptionHandler(PlatformAuthorizationDeniedException.class)
    ResponseEntity<Problem> authorizationDenied(
            PlatformAuthorizationDeniedException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "PLATFORM_AUTHORIZATION_DENIED",
                "Platform authorization denied", exception.getMessage(), request);
    }

    @ExceptionHandler(TenantExpiryInvalidException.class)
    ResponseEntity<Problem> invalidExpiry(TenantExpiryInvalidException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, TenantExpiryInvalidException.CODE,
                "Invalid Tenant expiry", exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Problem> invalidArgument(IllegalArgumentException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request validation failed", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyInvalidException.class)
    ResponseEntity<Problem> invalidIdempotencyKey(
            IdempotencyKeyInvalidException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, IdempotencyKeyInvalidException.CODE,
                "Invalid idempotency key", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<Problem> reusedIdempotencyKey(
            IdempotencyKeyReusedException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, IdempotencyKeyReusedException.CODE,
                "Idempotency key reused", exception.getMessage(), request);
    }

    @ExceptionHandler(TenantStateTransitionNotAllowedException.class)
    ResponseEntity<Problem> stateTransitionNotAllowed(
            TenantStateTransitionNotAllowedException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, TenantStateTransitionNotAllowedException.CODE,
                "Tenant state transition not allowed", exception.getMessage(), request);
    }

    @ExceptionHandler(TenantLifecycleException.class)
    ResponseEntity<Problem> tenantLifecycleFailure(
            TenantLifecycleException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "TENANT_NOT_FOUND", "TENANT_CREATION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "TENANT_SUSPENSION_PENDING", "TENANT_RESUME_PENDING",
                    "TENANT_SUSPENSION_RECOVERY_PENDING", "TENANT_CLOSURE_PENDING",
                    "TENANT_CLOSURE_RECOVERY_PENDING" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        ResponseEntity<Problem> response = problem(status, exception.code(),
                "Tenant lifecycle change failed", exception.getMessage(), request);
        if (status == HttpStatus.SERVICE_UNAVAILABLE || "IDEMPOTENCY_REQUEST_IN_PROGRESS".equals(exception.code())) {
            return ResponseEntity.status(status)
                    .header("Retry-After", Long.toString(Math.max(1, exception.retryAfterSeconds())))
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler(TenantAdministratorInitializationException.class)
    ResponseEntity<Problem> initializationFailure(
            TenantAdministratorInitializationException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "TENANT_NOT_FOUND", "TENANT_ADMIN_INITIALIZATION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "TENANT_ADMIN_INITIALIZATION_COMPENSATING" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        ResponseEntity<Problem> response = problem(
                status, exception.code(), "Tenant administrator initialization failed",
                exception.getMessage(), request);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            return ResponseEntity.status(status)
                    .header("Retry-After", Long.toString(Math.max(1, exception.retryAfterSeconds())))
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler(RemoteWorkflowUnavailableException.class)
    ResponseEntity<Problem> dependencyUnavailable(
            RemoteWorkflowUnavailableException exception, HttpServletRequest request) {
        ResponseEntity<Problem> response = problem(
                HttpStatus.SERVICE_UNAVAILABLE, "TENANT_ADMIN_INITIALIZATION_DEPENDENCY_UNAVAILABLE",
                "Tenant administrator initialization dependency unavailable", exception.getMessage(), request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(response.getBody());
    }

    @ExceptionHandler(AdministratorPasswordSetupException.class)
    ResponseEntity<Problem> administratorPasswordSetupFailure(
            AdministratorPasswordSetupException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "TENANT_NOT_FOUND", "PASSWORD_SETUP_RESEND_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "PASSWORD_SETUP_DELIVERY_PENDING" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        ResponseEntity<Problem> response = problem(
                status, exception.code(), "Tenant administrator password setup failed",
                exception.getMessage(), request);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            return ResponseEntity.status(status)
                    .header("Retry-After", Long.toString(Math.max(1, exception.retryAfterSeconds())))
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(response.getBody());
        }
        return response;
    }

    private static ResponseEntity<Problem> problem(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        String traceId = TenantCreationController.traceId(request);
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        Problem body = new Problem(
                URI.create("urn:saas.forge:problem:" + code.toLowerCase().replace('_', '-')),
                title, status.value(), code, detail, traceId);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    record Problem(URI type, String title, int status, String code, String detail, String traceId) {
    }
}
