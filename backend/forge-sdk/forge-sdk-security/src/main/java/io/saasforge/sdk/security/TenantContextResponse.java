package io.saasforge.sdk.security;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** 从已验证的 Tenant Realm JWT 读取当前请求上下文，不信任客户端传入的租户请求头。 */
public record TenantContextResponse(UUID identityId, UUID tenantId, UUID membershipId, long version) {
    /** 仅在已认证的请求线程中调用；后台任务不能隐式继承请求身份。 */
    public static TenantContextResponse current() {
        if (!(SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken authentication)) {
            throw new IllegalStateException("Tenant authentication is required");
        }
        var jwt = authentication.getToken();
        return new TenantContextResponse(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("tenant_id")),
                UUID.fromString(jwt.getClaimAsString("membership_id")),
                ((Number) jwt.getClaim("ver")).longValue());
    }
}
