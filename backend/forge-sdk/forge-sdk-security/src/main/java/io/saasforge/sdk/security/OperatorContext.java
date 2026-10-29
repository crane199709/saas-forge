package io.saasforge.sdk.security;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 从已验证的 Platform Realm JWT 读取当前 Operator；仅在 {@code forge.security.realm=platform} 的服务中可用。
 *
 * @param operatorId Token 的 {@code sub}
 * @param version Token 的 {@code ver}，按此版本读取 Operator 的平台角色明细
 */
public record OperatorContext(UUID operatorId, long version) {
    /** 仅在已认证的请求线程中调用；后台任务不能隐式继承请求身份。 */
    public static OperatorContext current() {
        if (!(SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken authentication)) {
            throw new IllegalStateException("Operator authentication is required");
        }
        var jwt = authentication.getToken();
        // PlatformJwtValidator 已保证 ver 存在且为数字。
        return new OperatorContext(UUID.fromString(jwt.getSubject()), ((Number) jwt.getClaim("ver")).longValue());
    }
}
