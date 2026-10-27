package io.saasforge.sdk.web;

import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ErrorCode;
import io.saasforge.sdk.core.api.ErrorEnvelopes;
import io.saasforge.sdk.core.api.FieldError;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 把 {@link ForgeException} 与框架异常映射为失败信封（统一接口格式规格 5.2）。固定状态码的处理方法标注
 * {@link ResponseStatus}，springdoc 据此把错误响应的 schema 写进每个接口的文档。
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String VALIDATION_PREFIX = "validation.";

    private final UserTipResolver userTips;

    ApiExceptionHandler(UserTipResolver userTips) {
        this.userTips = userTips;
    }

    @ExceptionHandler(ForgeException.class)
    ResponseEntity<ApiResponse<Void>> handleForge(ForgeException e) {
        ErrorCode code = e.errorCode();
        String errorMessage = e.errorMessage();
        if (code.httpStatus() >= 500) {
            log.error("Request failed with {}", code.code(), e);
            errorMessage = ErrorEnvelopes.INTERNAL_ERROR;
        }
        ApiResponse<Void> body = ApiResponse.error(
                code.httpStatus(), code.code(), errorMessage, userTips.resolve(code.code(), e.args(), locale()), null);
        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({BindException.class, HandlerMethodValidationException.class, InvalidPageQueryException.class})
    ApiResponse<Void> handleInvalid(Exception e) {
        List<FieldError> errors = fieldErrors(e);
        return error(FrameworkErrorCode.REQUEST_INVALID, errors.size() + " field(s) failed validation", errors);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        TypeMismatchException.class,
        ServletRequestBindingException.class
    })
    ApiResponse<Void> handleMalformed(Exception e) {
        log.debug("Malformed request", e);
        return error(
                FrameworkErrorCode.REQUEST_MALFORMED,
                "Malformed request: " + e.getClass().getSimpleName(),
                null);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ApiResponse<Void> handleNotFound(Exception e) {
        return error(FrameworkErrorCode.REQUEST_NOT_FOUND, "No route for " + routeOf(e), null);
    }

    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ApiResponse<Void> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletResponse response) {
        if (e.getSupportedHttpMethods() != null) {
            response.setHeader(
                    HttpHeaders.ALLOW,
                    String.join(
                            ", ",
                            e.getSupportedHttpMethods().stream()
                                    .map(Object::toString)
                                    .toList()));
        }
        return error(FrameworkErrorCode.REQUEST_METHOD_NOT_ALLOWED, "Method " + e.getMethod() + " not supported", null);
    }

    /**
     * 依赖的资源（Redis、数据库连接等）不可用：Spring 把各类连接失败统一为 {@link DataAccessResourceFailureException} 体系（如
     * {@code RedisConnectionFailureException}），按 503 返回，让前端提示稍后重试而不是当作未知错误；连接地址等细节只写日志。
     */
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(DataAccessResourceFailureException.class)
    ApiResponse<Void> handleUnavailable(DataAccessResourceFailureException e) {
        log.warn("Dependent resource unavailable", e);
        return error(FrameworkErrorCode.SYSTEM_UNAVAILABLE, ErrorEnvelopes.INTERNAL_ERROR, null);
    }

    /**
     * 兜底：其余实现 {@link ErrorResponse} 的框架异常（如 415、406）保留其状态码；未知异常一律 500，不向调用方暴露异常信息。
     */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    ApiResponse<Void> handleUnknown(Exception e) {
        if (e instanceof ErrorResponse response) {
            int status = response.getStatusCode().value();
            if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                log.warn("Service unavailable", e);
                return error(FrameworkErrorCode.SYSTEM_UNAVAILABLE, ErrorEnvelopes.INTERNAL_ERROR, null);
            }
            if (status < 500) {
                String code = FrameworkErrorCode.REQUEST_MALFORMED.code();
                return ApiResponse.error(
                        status, code, e.getClass().getSimpleName(), userTips.resolve(code, null, locale()), null);
            }
        }
        log.error("Unhandled exception", e);
        return error(FrameworkErrorCode.SYSTEM_ERROR, ErrorEnvelopes.INTERNAL_ERROR, null);
    }

    private ApiResponse<Void> error(FrameworkErrorCode code, String errorMessage, List<FieldError> errors) {
        return ApiResponse.error(
                code.httpStatus(), code.code(), errorMessage, userTips.resolve(code.code(), null, locale()), errors);
    }

    private List<FieldError> fieldErrors(Exception e) {
        Locale locale = locale();
        List<FieldError> result = new ArrayList<>();
        if (e instanceof InvalidPageQueryException invalid) {
            for (InvalidPageQueryException.Violation v : invalid.violations()) {
                result.add(new FieldError(v.field(), v.code(), userTips.resolve(v.code(), null, locale)));
            }
        } else if (e instanceof BindException bind) {
            addFieldErrors(bind, result, locale);
        } else if (e instanceof HandlerMethodValidationException validation) {
            validation.getParameterValidationResults().forEach(parameter -> {
                if (parameter instanceof Errors errors) {
                    addFieldErrors(errors, result, locale);
                } else {
                    String field = parameter.getMethodParameter().getParameterName();
                    parameter.getResolvableErrors().forEach(error -> result.add(fieldError(field, error, locale)));
                }
            });
        }
        return result;
    }

    private void addFieldErrors(Errors errors, List<FieldError> result, Locale locale) {
        errors.getFieldErrors().forEach(error -> result.add(fieldError(error.getField(), error, locale)));
    }

    /** 校验错误码取约束名（Spring 错误码列表中最通用的一项），如 {@code NotBlank} 映射为 {@code validation.notBlank}。 */
    private FieldError fieldError(String field, MessageSourceResolvable error, Locale locale) {
        String[] codes = error.getCodes();
        String constraint = codes == null || codes.length == 0 ? "invalid" : codes[codes.length - 1];
        String code = VALIDATION_PREFIX + Character.toLowerCase(constraint.charAt(0)) + constraint.substring(1);
        // 服务未定义该校验码文案时，用 Bean Validation 按请求语言插值的默认消息
        String tip = userTips.find(code, error.getArguments(), locale)
                .orElseGet(() -> error.getDefaultMessage() != null
                        ? error.getDefaultMessage()
                        : userTips.resolve(code, null, locale));
        return new FieldError(field, code, tip);
    }

    private static String routeOf(Exception e) {
        if (e instanceof NoResourceFoundException noResource) {
            return noResource.getHttpMethod() + " /" + noResource.getResourcePath();
        }
        NoHandlerFoundException noHandler = (NoHandlerFoundException) e;
        return noHandler.getHttpMethod() + " " + noHandler.getRequestURL();
    }

    private static Locale locale() {
        return LocaleContextHolder.getLocale();
    }
}
