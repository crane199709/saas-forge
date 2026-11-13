package io.saasforge.sdk.security;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 后台任务携带的授权身份。保留 JWT 的租户、Membership 与权限上下文，但不作为标准列操作人（ADR-0016）。
 * 任务发起人应显式写入 Audit Event，不能把后台写入伪装成仍在处理原 HTTP 请求。
 */
public final class BackgroundJwtAuthentication extends JwtAuthenticationToken {
    /**
     * 仅从已经验证的请求认证创建，不能用未认证 Token 建立后台身份。
     *
     * @param verified 已通过资源服务器验证的认证
     */
    public BackgroundJwtAuthentication(JwtAuthenticationToken verified) {
        super(verified.getToken(), verified.getAuthorities(), verified.getName());
        if (!verified.isAuthenticated()) {
            throw new IllegalArgumentException("Verified authentication required");
        }
    }
}
