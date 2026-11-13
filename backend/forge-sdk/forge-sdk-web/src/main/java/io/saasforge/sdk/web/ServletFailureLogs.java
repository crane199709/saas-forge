package io.saasforge.sdk.web;

import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ErrorEnvelopes;
import io.saasforge.sdk.core.api.FailureLogs;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.HandlerMapping;

/** 失败日志与响应共用请求属性，避免异常映射、安全写出和容器转发重复记录。 */
final class ServletFailureLogs {
    private ServletFailureLogs() {}

    static void serverError(Logger logger, ApiResponse<?> body, Level level, Throwable failure) {
        HttpServletRequest request =
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        // /error 转发时当前 span 已关闭，日志也必须从原请求的观测上下文获取 ID。
        String traceId = ServerHttpObservationFilter.findObservationContext(request)
                .map(ErrorEnvelopes::traceId)
                .orElse(null);
        write(logger, request, body.withTraceId(traceId), level, failure);
    }

    static void write(Logger logger, HttpServletRequest request, ApiResponse<?> body, Level level, Throwable failure) {
        String logged = FailureLogs.class.getName();
        if (body.httpStatus() < 400 || Boolean.TRUE.equals(request.getAttribute(logged))) {
            return;
        }
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route =
                original instanceof String path ? path : pattern == null ? request.getRequestURI() : pattern.toString();
        FailureLogs.write(logger, level, request.getMethod(), route, body, failure);
        request.setAttribute(logged, Boolean.TRUE);
    }
}
