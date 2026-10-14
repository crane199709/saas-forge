package io.saas.forge.tenantaccess.api;

import io.saas.forge.tenantaccess.application.organization.OrganizationUnitService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OrganizationUnitController.class)
public class OrganizationUnitExceptionHandler {
    @ExceptionHandler(OrganizationUnitService.Forbidden.class)
    ProblemDetail forbidden() { return problem(HttpStatus.FORBIDDEN, "ORGANIZATION_PERMISSION_DENIED"); }

    @ExceptionHandler(OrganizationUnitService.Invalid.class)
    ProblemDetail invalid() { return problem(HttpStatus.BAD_REQUEST, "ORGANIZATION_UNIT_INVALID"); }

    @ExceptionHandler(OrganizationUnitService.Missing.class)
    ProblemDetail missing() { return problem(HttpStatus.NOT_FOUND, "ORGANIZATION_UNIT_NOT_FOUND"); }

    @ExceptionHandler(OrganizationUnitService.Conflict.class)
    ProblemDetail conflict() { return problem(HttpStatus.CONFLICT, "ORGANIZATION_UNIT_CONFLICT"); }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail concurrentReference() { return problem(HttpStatus.CONFLICT, "ORGANIZATION_UNIT_CONFLICT"); }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail unavailable() { return problem(HttpStatus.SERVICE_UNAVAILABLE, "ORGANIZATION_UNAVAILABLE"); }

    private ProblemDetail problem(HttpStatus status, String code) {
        var result = ProblemDetail.forStatusAndDetail(status, code);
        result.setProperty("code", code);
        return result;
    }
}
