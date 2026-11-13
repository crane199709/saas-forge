package io.saasforge.iam.security.service;

import java.util.stream.Stream;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

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

    /**
     * 在主体上补记已完成 MFA，用于被 MFA Requirement 拦下的身份当场绑定之后（ADR-0019）。与登录时建立的主体一样只保留名称与权限。
     *
     * @param principal 登录会话主体，或授权中保存的主体
     * @return 带有 {@link #AUTHORITY} 的新主体；原主体已带有时原样返回
     */
    public static Authentication grant(Authentication principal) {
        if (completed(principal)) {
            return principal;
        }
        return withAuthorities(
                principal,
                Stream.concat(
                        principal.getAuthorities().stream().map(GrantedAuthority::getAuthority), Stream.of(AUTHORITY)));
    }

    /**
     * 去掉主体上的已完成 MFA，用于解绑 Authenticator 之后：解绑者不再持有第二因素，现存授权不能继续凭旧记录进入有 MFA Requirement 的租户。
     *
     * @param principal 授权中保存的主体
     * @return 不带 {@link #AUTHORITY} 的新主体
     */
    public static Authentication revoke(Authentication principal) {
        return withAuthorities(
                principal,
                principal.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .filter(authority -> !AUTHORITY.equals(authority)));
    }

    private static Authentication withAuthorities(Authentication principal, Stream<String> authorities) {
        var user = User.withUsername(principal.getName())
                .password("")
                .authorities(authorities.toArray(String[]::new))
                .build();
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }
}
