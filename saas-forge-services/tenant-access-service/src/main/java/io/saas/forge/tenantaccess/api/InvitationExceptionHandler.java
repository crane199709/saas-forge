package io.saas.forge.tenantaccess.api;

import io.saas.forge.tenantaccess.application.invitation.InvitationFailure;
import io.saas.forge.tenantaccess.application.invitation.InvitationDeliveryException;
import io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saas.forge.tenantaccess.domain.invitation.InvitationUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {InvitationController.class, InvitationPasswordSetupController.class})
public class InvitationExceptionHandler {
    @ExceptionHandler(InvitationFailure.class)
    ResponseEntity<Problem> invitation(InvitationFailure failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "INVITATION_NOT_FOUND", "INVITATION_INVALID" -> HttpStatus.NOT_FOUND;
            case "TENANT_NOT_ACCESSIBLE" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.CONFLICT;
        };
        return problem(status, failure.code(), request);
    }

    @ExceptionHandler(InvitationUnavailableException.class)
    ResponseEntity<Problem> unavailable(InvitationUnavailableException ignored, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "INVITATION_NOT_PENDING", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Problem> invalid(IllegalArgumentException ignored, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVITATION_EMAIL_INVALID", request);
    }

    @ExceptionHandler({DataAccessException.class, InvitationDeliveryException.class,
            RemoteWorkflowUnavailableException.class})
    ResponseEntity<Problem> dependencyUnavailable(RuntimeException ignored, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "INVITATION_DEPENDENCY_UNAVAILABLE", request);
    }

    private static ResponseEntity<Problem> problem(HttpStatus status, String code, HttpServletRequest request) {
        String traceId = TenantCreationController.traceId(request);
        if (traceId == null) traceId = UUID.randomUUID().toString().replace("-", "");
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem(URI.create("urn:saas.forge:problem:" + code.toLowerCase().replace('_', '-')),
                        "Invitation request failed", status.value(), code, code, traceId));
    }

    record Problem(URI type, String title, int status, String code, String detail, String traceId) {}
}
