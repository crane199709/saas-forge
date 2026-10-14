package io.saas.forge.starter.security;

import io.saas.forge.sdk.permission.PermissionChecker;
import io.saas.forge.sdk.permission.PermissionDeniedException;
import io.saas.forge.sdk.permission.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 在已复验 User Access Token 的 MVC 业务方法之前执行逐次权威授权。 */
public final class PermissionMvcInterceptor implements HandlerInterceptor {
    private final PermissionChecker checker;

    public PermissionMvcInterceptor(PermissionChecker checker) {
        this.checker = checker;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            RequirePermission permission = method.getMethodAnnotation(RequirePermission.class);
            if (permission == null) throw new PermissionDeniedException();
            checker.require(permission.value(), request.getHeader(HttpHeaders.AUTHORIZATION));
        }
        return true;
    }
}
