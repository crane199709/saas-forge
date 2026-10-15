package io.saas.forge.tenantaccess.api;

import io.saas.forge.tenantaccess.application.brand.BrandAssetService;
import io.saas.forge.tenantaccess.application.brand.BrandPng;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkException;

@RestControllerAdvice(assignableTypes = {BrandAssetController.class, BrandAssetPolicyController.class})
public class BrandAssetExceptionHandler {
    @ExceptionHandler(BrandPng.Invalid.class)
    ResponseEntity<Problem> invalid(HttpServletRequest request) { return problem(HttpStatus.BAD_REQUEST, "BRAND_PNG_INVALID", request); }

    @ExceptionHandler(BrandPng.TooLarge.class)
    ResponseEntity<Problem> tooLarge(HttpServletRequest request) { return problem(HttpStatus.PAYLOAD_TOO_LARGE, "BRAND_PNG_TOO_LARGE", request); }

    @ExceptionHandler(BrandAssetService.Forbidden.class)
    ResponseEntity<Problem> forbidden(HttpServletRequest request) { return problem(HttpStatus.FORBIDDEN, "BRAND_ASSET_PERMISSION_DENIED", request); }

    @ExceptionHandler(BrandAssetService.Missing.class)
    ResponseEntity<Problem> missing(HttpServletRequest request) { return problem(HttpStatus.NOT_FOUND, "BRAND_ASSET_NOT_FOUND", request); }

    @ExceptionHandler(SdkException.class)
    ResponseEntity<Problem> unavailable(HttpServletRequest request) { return problem(HttpStatus.SERVICE_UNAVAILABLE, "BRAND_STORAGE_UNAVAILABLE", request); }

    private ResponseEntity<Problem> problem(HttpStatus status, String code, HttpServletRequest request) {
        String traceId = TenantCreationController.traceId(request);
        if (traceId == null) traceId = UUID.randomUUID().toString().replace("-", "");
        var body = new Problem(URI.create("urn:saas.forge:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')),
                "Brand asset request failed", status.value(), code, code, traceId);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    record Problem(URI type, String title, int status, String code, String detail, String traceId) {}
}
