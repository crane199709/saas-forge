package io.saasforge.sdk.security;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** 从已验证的 Platform Realm JWT 读取当前 Operator；仅在 {@code forge.security.realm=platform} 的服务中可用。 */
public record OperatorContext(UUID operatorId) {
    /** 仅在已认证的请求线程中调用；后台任务不能隐式继承请求身份。 */
    public static OperatorContext current() {
        if (!(SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken authentication)) {
            throw new IllegalStateException("Operator authentication is required");
        }
        return new OperatorContext(UUID.fromString(authentication.getToken().getSubject()));
    }
}
