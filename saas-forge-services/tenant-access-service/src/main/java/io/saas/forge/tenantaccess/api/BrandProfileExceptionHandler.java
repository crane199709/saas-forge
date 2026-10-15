package io.saas.forge.tenantaccess.api;

import io.saas.forge.tenantaccess.application.brand.BrandProfileService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = BrandProfileController.class)
public class BrandProfileExceptionHandler {
    @ExceptionHandler(BrandProfileController.BrandProfilePreconditionRequired.class)
    ProblemDetail preconditionRequired(HttpServletRequest request) { return problem(HttpStatus.PRECONDITION_REQUIRED, "BRAND_PROFILE_PRECONDITION_REQUIRED", request); }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingRevision(MissingRequestHeaderException failure, HttpServletRequest request) {
        if (org.springframework.http.HttpHeaders.IF_MATCH.equalsIgnoreCase(failure.getHeaderName()))
            return preconditionRequired(request);
        return problem(HttpStatus.BAD_REQUEST, "BRAND_PROFILE_INVALID", request);
    }

    @ExceptionHandler(BrandProfileService.Stale.class)
    ProblemDetail stale(HttpServletRequest request) { return problem(HttpStatus.PRECONDITION_FAILED, "BRAND_PROFILE_STALE", request); }

    @ExceptionHandler(BrandProfileService.AssetUnavailable.class)
    ProblemDetail assetUnavailable(HttpServletRequest request) { return problem(HttpStatus.CONFLICT, "BRAND_PROFILE_ASSET_UNAVAILABLE", request); }

    @ExceptionHandler(BrandProfileService.Invalid.class)
    ProblemDetail invalid(HttpServletRequest request) { return problem(HttpStatus.BAD_REQUEST, "BRAND_PROFILE_INVALID", request); }

    @ExceptionHandler(BrandProfileService.Forbidden.class)
    ProblemDetail forbidden(HttpServletRequest request) { return problem(HttpStatus.FORBIDDEN, "BRAND_PROFILE_PERMISSION_DENIED", request); }

    private static ProblemDetail problem(HttpStatus status, String code, HttpServletRequest request) {
        var result = ProblemDetail.forStatusAndDetail(status, code);
        result.setType(URI.create("urn:saas.forge:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        result.setTitle("Brand profile request failed");
        result.setProperty("code", code);
        String traceId = TenantCreationController.traceId(request);
        result.setProperty("traceId", traceId == null ? UUID.randomUUID().toString().replace("-", "") : traceId);
        return result;
    }
}
