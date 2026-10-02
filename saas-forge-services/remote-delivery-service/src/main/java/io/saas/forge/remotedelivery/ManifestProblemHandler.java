package io.saas.forge.remotedelivery;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class ManifestProblemHandler {
    @ExceptionHandler(ManifestException.class)
    ResponseEntity<Problem> domain(ManifestException error,HttpServletRequest request) {
        return problem(error.status(),error.code(),request);
    }
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class,
        org.springframework.web.method.annotation.HandlerMethodValidationException.class,
        jakarta.validation.ConstraintViolationException.class})
    ResponseEntity<Problem> invalid(Exception error,HttpServletRequest request) { return problem(400,"VALIDATION_FAILED",request); }
    @ExceptionHandler(io.saas.forge.sdk.tenant.TenantContextUnavailableException.class)
    ResponseEntity<Problem> tenantMissing(Exception error,HttpServletRequest request) { return problem(403,"TENANT_CONTEXT_REQUIRED",request); }
    @ExceptionHandler(io.saas.forge.sdk.auth.UserAccessTokenInvalidException.class)
    ResponseEntity<Problem> tokenInvalid(Exception error,HttpServletRequest request) { return problem(401,"ACCESS_TOKEN_INVALID",request); }
    private ResponseEntity<Problem> problem(int status,String code,HttpServletRequest request) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem("about:blank",code,status,code,code,io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId()));
    }
    public record Problem(String type,String title,int status,String detail,String code,String traceId) {}
}
