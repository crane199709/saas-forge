package io.saas.forge.example;

import io.saas.forge.sdk.tenant.TenantContextUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestControllerAdvice
class ProjectExceptionHandler {
    @ExceptionHandler(ProjectException.class)
    ResponseEntity<ProjectProblem> projectFailure(ProjectException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), request,
                exception.status() == 400 ? List.of(new ProjectProblem.FieldError("", exception.code(), exception.getMessage())) : null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProjectProblem> invalidFields(MethodArgumentNotValidException exception, HttpServletRequest request) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ProjectProblem.FieldError("/" + error.getField(), "INVALID_FIELD", "The field is invalid."))
                .toList();
        return response(400, "VALIDATION_FAILED", "Request fields are invalid.", request, errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProjectProblem> invalidJson(HttpServletRequest request) {
        return response(400, "VALIDATION_FAILED", "The JSON body is invalid.", request,
                List.of(new ProjectProblem.FieldError("", "INVALID_BODY", "The JSON body is invalid.")));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProjectProblem> unavailable(HttpServletRequest request) {
        // SQL 与连接异常可能包含内部数据；公共响应只保留稳定错误语义。
        return response(503, "INFRASTRUCTURE_UNAVAILABLE", "The database is temporarily unavailable.", request, null);
    }

    @ExceptionHandler(TenantContextUnavailableException.class)
    ResponseEntity<ProjectProblem> noTenant(HttpServletRequest request) {
        return response(403, "ACCESS_CONTEXT_UNAVAILABLE", "A tenant user context is required.", request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProjectProblem> unsupportedType(HttpServletRequest request) {
        return response(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json.", request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ProjectProblem> unacceptable(HttpServletRequest request) {
        return response(406, "NOT_ACCEPTABLE", "The requested representation is unavailable.", request, null);
    }

    private static ResponseEntity<ProjectProblem> response(int status, String code, String detail,
            HttpServletRequest request, List<ProjectProblem.FieldError> errors) {
        String parent = request.getHeader("traceparent");
        String trace = parent != null && parent.matches("[0-9a-f]{2}-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}")
                ? parent.substring(3, 35) : UUID.randomUUID().toString().replace("-", "");
        var problem = new ProjectProblem("urn:saas.forge:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                HttpStatus.valueOf(status).getReasonPhrase(), status, code, detail, trace, errors);
        var response = ResponseEntity.status(status).contentType(MediaType.parseMediaType("application/problem+json;charset=UTF-8"));
        if (code.equals("IDEMPOTENCY_REQUEST_IN_PROGRESS")) response.header("Retry-After", "1");
        return response.body(problem);
    }
}
