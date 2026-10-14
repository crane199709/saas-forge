package io.saas.forge.tenantaccess.api;

import io.saas.forge.tenantaccess.application.brand.BrandProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = BrandProfileController.class)
public class BrandProfileExceptionHandler {
    @ExceptionHandler(BrandProfileController.BrandProfilePreconditionRequired.class)
    ProblemDetail preconditionRequired() { return problem(HttpStatus.PRECONDITION_REQUIRED, "BRAND_PROFILE_PRECONDITION_REQUIRED"); }

    @ExceptionHandler(BrandProfileService.Stale.class)
    ProblemDetail stale() { return problem(HttpStatus.PRECONDITION_FAILED, "BRAND_PROFILE_STALE"); }

    @ExceptionHandler(BrandProfileService.AssetUnavailable.class)
    ProblemDetail assetUnavailable() { return problem(HttpStatus.CONFLICT, "BRAND_PROFILE_ASSET_UNAVAILABLE"); }

    @ExceptionHandler(BrandProfileService.Invalid.class)
    ProblemDetail invalid() { return problem(HttpStatus.BAD_REQUEST, "BRAND_PROFILE_INVALID"); }

    @ExceptionHandler(BrandProfileService.Forbidden.class)
    ProblemDetail forbidden() { return problem(HttpStatus.FORBIDDEN, "BRAND_PROFILE_PERMISSION_DENIED"); }

    private static ProblemDetail problem(HttpStatus status, String code) {
        var result = ProblemDetail.forStatusAndDetail(status, code);
        result.setProperty("code", code);
        return result;
    }
}
