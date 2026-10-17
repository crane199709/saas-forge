package io.saas.forge.tenantaccess.api;

import io.saas.forge.tenantaccess.application.organization.OrganizationUnitService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {OrganizationUnitController.class, MemberDirectoryController.class})
public class OrganizationUnitExceptionHandler {
    @ExceptionHandler(OrganizationUnitService.Forbidden.class)
    ProblemDetail forbidden(HttpServletRequest request) { return problem(HttpStatus.FORBIDDEN, "ORGANIZATION_PERMISSION_DENIED", request); }

    @ExceptionHandler(OrganizationUnitService.Invalid.class)
    ProblemDetail invalid(HttpServletRequest request) { return problem(HttpStatus.BAD_REQUEST, "ORGANIZATION_UNIT_INVALID", request); }

    @ExceptionHandler(OrganizationUnitService.Missing.class)
    ProblemDetail missing(HttpServletRequest request) { return problem(HttpStatus.NOT_FOUND, "ORGANIZATION_UNIT_NOT_FOUND", request); }

    @ExceptionHandler(OrganizationUnitService.Conflict.class)
    ProblemDetail conflict(HttpServletRequest request) { return problem(HttpStatus.CONFLICT, "ORGANIZATION_UNIT_CONFLICT", request); }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail concurrentReference(HttpServletRequest request) {
        return organizationRoute(request)
                ? problem(HttpStatus.CONFLICT, "ORGANIZATION_UNIT_CONFLICT", request)
                : problem(HttpStatus.INTERNAL_SERVER_ERROR, "TENANT_ACCESS_UNAVAILABLE", request);
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail unavailable(HttpServletRequest request) {
        return organizationRoute(request)
                ? problem(HttpStatus.SERVICE_UNAVAILABLE, "ORGANIZATION_UNAVAILABLE", request)
                : problem(HttpStatus.INTERNAL_SERVER_ERROR, "TENANT_ACCESS_UNAVAILABLE", request);
    }

    private static boolean organizationRoute(HttpServletRequest request) {
        return request.getRequestURI().contains("/organization-units")
                || request.getRequestURI().contains("/organization/units");
    }

    private ProblemDetail problem(HttpStatus status, String code, HttpServletRequest request) {
        var result = ProblemDetail.forStatusAndDetail(status, code);
        result.setType(URI.create("urn:saas.forge:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        result.setTitle("Organization request failed");
        result.setProperty("code", code);
        String traceId = TenantCreationController.traceId(request);
        result.setProperty("traceId", traceId == null ? UUID.randomUUID().toString().replace("-", "") : traceId);
        return result;
    }
}
