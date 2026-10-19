package io.saas.forge.tenantaccess.api;

import io.saas.forge.tenantaccess.application.membership.MembershipDisableFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MemberDirectoryController.class)
public final class MembershipDisableExceptionHandler {
    @ExceptionHandler(MembershipDisableFailure.class)
    ResponseEntity<Problem> failure(MembershipDisableFailure failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "MEMBERSHIP_DISABLE_FORBIDDEN", "MEMBERSHIP_ADMINISTRATOR_PROTECTED" -> HttpStatus.FORBIDDEN;
            case "MEMBERSHIP_NOT_FOUND", "TENANT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_UUID_V7" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        String traceId = TenantCreationController.traceId(request);
        if (traceId == null) traceId = UUID.randomUUID().toString().replace("-", "");
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem(URI.create("urn:saas.forge:problem:" + failure.code().toLowerCase().replace('_', '-')),
                        "Membership disable failed", status.value(), failure.code(), failure.code(), traceId));
    }

    record Problem(URI type, String title, int status, String code, String detail, String traceId) {}
}
