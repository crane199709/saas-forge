package io.saas.forge.tenantaccess.api;

import io.saas.forge.tenantaccess.application.brand.BrandAssetService;
import io.saas.forge.tenantaccess.application.brand.BrandPng;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkException;

@RestControllerAdvice(assignableTypes = {BrandAssetController.class, BrandAssetPolicyController.class})
public class BrandAssetExceptionHandler {
    @ExceptionHandler(BrandPng.Invalid.class)
    ProblemDetail invalid() { return problem(HttpStatus.BAD_REQUEST, "BRAND_PNG_INVALID"); }

    @ExceptionHandler(BrandPng.TooLarge.class)
    ProblemDetail tooLarge() { return problem(HttpStatus.PAYLOAD_TOO_LARGE, "BRAND_PNG_TOO_LARGE"); }

    @ExceptionHandler(BrandAssetService.Forbidden.class)
    ProblemDetail forbidden() { return problem(HttpStatus.FORBIDDEN, "BRAND_ASSET_PERMISSION_DENIED"); }

    @ExceptionHandler(BrandAssetService.Missing.class)
    ProblemDetail missing() { return problem(HttpStatus.NOT_FOUND, "BRAND_ASSET_NOT_FOUND"); }

    @ExceptionHandler(SdkException.class)
    ProblemDetail unavailable() { return problem(HttpStatus.SERVICE_UNAVAILABLE, "BRAND_STORAGE_UNAVAILABLE"); }

    private ProblemDetail problem(HttpStatus status, String code) {
        var result = ProblemDetail.forStatusAndDetail(status, code);
        result.setProperty("code", code);
        return result;
    }
}
