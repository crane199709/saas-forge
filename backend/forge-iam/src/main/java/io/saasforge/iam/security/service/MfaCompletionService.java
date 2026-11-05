package io.saasforge.iam.security.service;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Service;

/**
 * 把授权补记为已完成 MFA：被 MFA Requirement 拦下的身份凭 Access Token 当场绑定 Authenticator 后，该 Token 所属授权（含 Refresh
 * Token 链）视为已完成 MFA，随后的 switch 与 refresh 据此放行（ADR-0019）。
 */
@Service
public class MfaCompletionService {
    private static final Logger log = LoggerFactory.getLogger(MfaCompletionService.class);

    private final RedisAuthorizationService authorizations;
    private final StringRedisTemplate redis;

    MfaCompletionService(RedisAuthorizationService authorizations, StringRedisTemplate redis) {
        this.authorizations = authorizations;
        this.redis = redis;
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
        String owner = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, Duration.ofSeconds(30)))) {
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
            redis.execute(
                    new DefaultRedisScript<Long>(
                            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                            Long.class),
                    List.of(key),
                    owner);
        }
    }
}
