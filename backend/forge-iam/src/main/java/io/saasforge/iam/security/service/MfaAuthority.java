package io.saasforge.iam.security.service;

import org.springframework.security.core.Authentication;

/**
 * 登录会话主体上表示「本次认证已完成 MFA」的权限。
 *
 * <p>授权码流程把会话主体原样存入授权，Refresh Token 链沿用同一授权，refresh 与 switch 因此读到的是建立授权时的认证结果，
 * 而不是浏览器会话的当前状态（ADR-0019）。未绑定 Authenticator 的身份不带此权限，即「未绑定、无需 MFA」。
 */
public final class MfaAuthority {
    /** 权限名。 */
    public static final String AUTHORITY = "MFA";

    private MfaAuthority() {}

    /**
     * 主体是否已完成 MFA。
     *
     * @param principal 登录会话主体，或授权中保存的主体
     * @return 带有 {@link #AUTHORITY} 时为 true
     */
    public static boolean completed(Authentication principal) {
        return principal != null
                && principal.getAuthorities().stream().anyMatch(granted -> AUTHORITY.equals(granted.getAuthority()));
    }
}
