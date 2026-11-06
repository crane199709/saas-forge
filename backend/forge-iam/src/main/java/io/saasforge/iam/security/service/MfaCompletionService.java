package io.saasforge.iam.security.service;

import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Service;

/**
 * 改写授权上的 MFA 记录（ADR-0019）：被 MFA Requirement 拦下的身份凭 Access Token 当场绑定 Authenticator 后，该 Token 所属授权（含 Refresh
 * Token 链）视为已完成 MFA，随后的 switch 与 refresh 据此放行；解绑后撤销现存的记录，之后进不了有 MFA Requirement 的 Tenant（账号安全规格 2.2）。
 */
@Service
public class MfaCompletionService {
    private static final Logger log = LoggerFactory.getLogger(MfaCompletionService.class);

    private final RedisAuthorizationService authorizations;
    private final LoginSessionRegistry loginSessions;

    MfaCompletionService(RedisAuthorizationService authorizations, LoginSessionRegistry loginSessions) {
        this.authorizations = authorizations;
        this.loginSessions = loginSessions;
    }

    /**
     * 把 Access Token 所属授权记为已完成 MFA。授权已失效、已记过或不属于该身份时不做修改。
     *
     * @param identityId 刚完成绑定的 Identity，须与授权主体一致
     * @param accessToken 本次绑定请求所用的 Access Token
     */
    public void completeAuthorization(UUID identityId, String accessToken) {
        OAuth2Authorization found = authorizations.findByToken(accessToken, OAuth2TokenType.ACCESS_TOKEN);
        if (found == null) return;
        // 与换码、刷新互斥：并发刷新轮换了 Token 后，用旧快照保存会把轮换掉的 Token 写回。
        String key = RedisAuthorizationService.grantLockKey(found.getId());
        String owner = authorizations.tryLock(key);
        if (owner == null) {
            // 绑定已生效，只是这条授权没能补记；重新登录（此时须完成 MFA）即可进入。
            log.warn("Authorization {} is busy, MFA completion skipped", found.getId());
            return;
        }
        try {
            OAuth2Authorization authorization = authorizations.findById(found.getId());
            if (authorization == null) return;
            Authentication principal = authorization.getAttribute(Principal.class.getName());
            if (principal == null
                    || !identityId.toString().equals(principal.getName())
                    || MfaAuthority.completed(principal)) {
                return;
            }
            authorizations.save(OAuth2Authorization.from(authorization)
                    .attribute(Principal.class.getName(), MfaAuthority.grant(principal))
                    .build());
        } finally {
            authorizations.unlock(key, owner);
        }
    }

    /**
     * 撤销 Identity 现存的全部 MFA 记录，解绑 Authenticator 后调用：授权上的标记逐条清除，保留授权以便继续使用没有要求的 Tenant；
     * 登录会话直接终止，否则它随后走授权码流程仍会得到记为已完成 MFA 的新授权。
     *
     * @param identityId 解绑的 Identity
     */
    public void revokeCompletions(UUID identityId) {
        String principalName = identityId.toString();
        authorizations.authorizationIdsOf(principalName).forEach(this::revokeCompletion);
        loginSessions.invalidateAll(principalName);
    }

    private void revokeCompletion(String authorizationId) {
        String key = RedisAuthorizationService.grantLockKey(authorizationId);
        String owner = authorizations.tryLock(key);
        if (owner == null) {
            // 正在换码或刷新：不能跳过，否则这条授权继续带着标记。删除后在途操作保存失败，须重新登录。
            OAuth2Authorization busy = authorizations.findById(authorizationId);
            if (busy != null) authorizations.remove(busy);
            return;
        }
        try {
            OAuth2Authorization authorization = authorizations.findById(authorizationId);
            if (authorization == null) return;
            Authentication principal = authorization.getAttribute(Principal.class.getName());
            if (!MfaAuthority.completed(principal)) return;
            authorizations.save(OAuth2Authorization.from(authorization)
                    .attribute(Principal.class.getName(), MfaAuthority.revoke(principal))
                    .build());
        } finally {
            authorizations.unlock(key, owner);
        }
    }
}
