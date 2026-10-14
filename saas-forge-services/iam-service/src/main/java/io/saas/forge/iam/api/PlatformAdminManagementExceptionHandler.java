package io.saas.forge.iam.api;

import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryUnavailableException;
import io.saas.forge.iam.application.client.OAuthClientManagementAuthorizationException;
import io.saas.forge.iam.application.authorization.PlatformAdminRevokeException;
import io.saas.forge.iam.application.authentication.RevocationIndexUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PlatformAdministratorsController.class)
public class PlatformAdminManagementExceptionHandler {
    @ExceptionHandler(PlatformAdminRevokeException.class)
    ResponseEntity<Problem> revoke(PlatformAdminRevokeException error, HttpServletRequest request) {
        return switch (error.code()) {
            case REQUEST_CONFLICT -> problem(HttpStatus.CONFLICT, "PLATFORM_ADMIN_REVOKE_REQUEST_CONFLICT", request);
            case TARGET_UNAVAILABLE -> problem(HttpStatus.NOT_FOUND, "PLATFORM_ADMIN_NOT_FOUND", request);
            case LAST_PASSWORD_ADMIN -> problem(HttpStatus.CONFLICT, "LAST_PASSWORD_PLATFORM_ADMIN", request);
        };
    }

    @ExceptionHandler(RevocationIndexUnavailableException.class)
    ResponseEntity<Problem> revocationUnavailable(RevocationIndexUnavailableException error,
            HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, RevocationIndexUnavailableException.CODE, request);
    }
    @ExceptionHandler(OAuthClientManagementAuthorizationException.class)
    ResponseEntity<Problem> authorization(OAuthClientManagementAuthorizationException error,
            HttpServletRequest request) {
        HttpStatus status = "ACCESS_TOKEN_INVALID".equals(error.code())
                ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        return problem(status, error.code(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Problem> invalid(IllegalArgumentException error, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Problem> conflict(IllegalStateException error, HttpServletRequest request) {
        if (!"PLATFORM_ADMIN_GRANT_REQUEST_CONFLICT".equals(error.getMessage())) throw error;
        return problem(HttpStatus.CONFLICT, "PLATFORM_ADMIN_GRANT_REQUEST_CONFLICT", request);
    }

    @ExceptionHandler(PasswordSetupDeliveryUnavailableException.class)
    ResponseEntity<Problem> deliveryUnavailable(PasswordSetupDeliveryUnavailableException error,
            HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "PASSWORD_SETUP_DELIVERY_UNAVAILABLE", request);
    }

    private static ResponseEntity<Problem> problem(HttpStatus status, String code, HttpServletRequest request) {
        String traceId = OAuthClientsController.traceId(request);
        if (traceId == null) traceId = UUID.randomUUID().toString().replace("-", "");
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem(URI.create("urn:saas.forge:problem:" + code.toLowerCase().replace('_', '-')),
                        "Platform administrator request failed", status.value(), code, traceId));
    }

    record Problem(URI type, String title, int status, String code, String traceId) { }
}
