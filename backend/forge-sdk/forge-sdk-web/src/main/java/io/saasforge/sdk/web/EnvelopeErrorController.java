package io.saasforge.sdk.web;

import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ErrorEnvelopes;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 取代 Boot 的 {@code BasicErrorController}：过滤器层（如 Spring Session、Token 端点的 Redis 访问）抛出的异常或 {@code sendError}
 * 不经过 MVC 异常处理，由容器转发到 {@code /error}，这里同样输出信封。
 *
 * <p>有异常时原样重新抛出，交给 {@link ApiExceptionHandler} 按同一套规则映射（如资源故障为 503），不在此重复映射表。
 */
@RestController
class EnvelopeErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeErrorController.class);

    private final UserTipResolver userTips;

    EnvelopeErrorController(UserTipResolver userTips) {
        this.userTips = userTips;
    }

    @RequestMapping("${server.error.path:${error.path:/error}}")
    ApiResponse<Void> error(HttpServletRequest request) throws Exception {
        Object error = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (error instanceof Exception exception) {
            throw exception;
        }
        if (error instanceof Throwable throwable) {
            throw new IllegalStateException("Unhandled error", throwable);
        }
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer value ? value : 500;
        FrameworkErrorCode code = codeOf(status);
        // 未单独定义 code 的 4xx 保留实际状态码，其余按错误码固定的状态码输出
        int httpStatus = code == FrameworkErrorCode.REQUEST_MALFORMED ? status : code.httpStatus();
        String errorMessage =
                httpStatus >= 500 ? ErrorEnvelopes.INTERNAL_ERROR : "Request failed with status " + status;
        ApiResponse<Void> body = ApiResponse.error(
                httpStatus,
                code.code(),
                errorMessage,
                userTips.resolve(code.code(), null, LocaleContextHolder.getLocale()),
                null);
        if (httpStatus >= 500) {
            ServletFailureLogs.serverError(log, body, httpStatus == 503 ? Level.WARN : Level.ERROR, null);
        }
        return body;
    }

    private static FrameworkErrorCode codeOf(int status) {
        return switch (status) {
            case 401 -> FrameworkErrorCode.AUTH_UNAUTHENTICATED;
            case 403 -> FrameworkErrorCode.AUTH_FORBIDDEN;
            case 404 -> FrameworkErrorCode.REQUEST_NOT_FOUND;
            case 405 -> FrameworkErrorCode.REQUEST_METHOD_NOT_ALLOWED;
            case 502, 503, 504 -> FrameworkErrorCode.SYSTEM_UNAVAILABLE;
            default ->
                status >= 400 && status < 500 ? FrameworkErrorCode.REQUEST_MALFORMED : FrameworkErrorCode.SYSTEM_ERROR;
        };
    }
}
