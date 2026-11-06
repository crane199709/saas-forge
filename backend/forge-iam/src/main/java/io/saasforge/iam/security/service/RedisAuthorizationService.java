package io.saasforge.iam.security.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Service;

@Service
public class RedisAuthorizationService implements OAuth2AuthorizationService {
    private static final Duration GRANT_LOCK_TTL = Duration.ofSeconds(30);
    // 主体到授权标识的索引，供改密码后吊销该主体的全部授权；索引只延长不缩短，覆盖其中最晚过期的授权。
    private static final DefaultRedisScript<Long> INDEX_PRINCIPAL = new DefaultRedisScript<>(
            "redis.call('sadd', KEYS[1], ARGV[1]) "
                    + "if redis.call('pttl', KEYS[1]) < tonumber(ARGV[2]) then redis.call('pexpire', KEYS[1], ARGV[2]) end "
                    + "return 1",
            Long.class);

    // 只释放自己持有的锁：持有者超时后锁可能已被别人取得。
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final JdkSerializationRedisSerializer serializer = new JdkSerializationRedisSerializer();

    RedisAuthorizationService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        var tokens = tokens(authorization);
        Instant expires = tokens.stream()
                .map(OAuth2Token::getExpiresAt)
                .max(Instant::compareTo)
                .orElse(Instant.now().plusSeconds(300));
        Duration ttl = Duration.between(Instant.now(), expires);
        if (ttl.isNegative() || ttl.isZero()) {
            remove(authorization);
            return;
        }
        var previous = findById(authorization.getId());
        if (previous != null) {
            for (OAuth2Token old : tokens(previous)) {
                if (tokens.stream().noneMatch(token -> token.getTokenValue().equals(old.getTokenValue()))) {
                    // 轮换后保留旧刷新凭证的摘要关联，直到其原始过期时间，以识别泄漏后的重放。
                    if (old instanceof OAuth2RefreshToken) {
                        Duration remaining = Duration.between(Instant.now(), old.getExpiresAt());
                        if (!remaining.isNegative() && !remaining.isZero()) {
                            redis.opsForValue()
                                    .set(usedRefreshKey(old.getTokenValue()), authorization.getId(), remaining);
                        }
                    }
                    redis.delete(tokenKey(old.getTokenValue()));
                }
            }
        }
        String key = authorizationKey(authorization.getId());
        String value = Base64.getEncoder().encodeToString(serializer.serialize(authorization));
        if (authorization.getAccessToken() == null) {
            redis.opsForValue().set(key, value, ttl);
        } else if (!Boolean.TRUE.equals(redis.opsForValue().setIfPresent(key, value, ttl))) {
            // 换码/刷新只能更新已有授权，原子 XX 防止在途请求复活被重放检测删除的授权。
            throw new OAuth2AuthenticationException("invalid_grant");
        }
        for (OAuth2Token token : tokens) {
            Duration tokenTtl = Duration.between(Instant.now(), token.getExpiresAt());
            if (!tokenTtl.isNegative() && !tokenTtl.isZero()) {
                redis.opsForValue().set(tokenKey(token.getTokenValue()), authorization.getId(), tokenTtl);
            }
        }
        redis.execute(
                INDEX_PRINCIPAL,
                List.of(principalKey(authorization.getPrincipalName())),
                authorization.getId(),
                String.valueOf(ttl.toMillis()));
    }

    /**
     * 删除主体的全部授权（含授权码与 Refresh Token 链），改密码或重设密码后调用；已在途的换码或刷新因授权不存在而失败。
     *
     * @param principalName Identity 或 Operator 标识
     * @param keepAuthorizationId 保留的授权，如发起改密码的这一授权；为 null 时全部删除
     */
    public void removeByPrincipal(String principalName, String keepAuthorizationId) {
        String key = principalKey(principalName);
        Set<String> ids = redis.opsForSet().members(key);
        if (ids == null) return;
        for (String id : ids) {
            if (id.equals(keepAuthorizationId)) continue;
            OAuth2Authorization authorization = findById(id);
            if (authorization != null) remove(authorization);
            redis.opsForSet().remove(key, id);
        }
    }

    /**
     * 主体现存的授权标识，可能包含已过期的授权。
     *
     * @param principalName Identity 或 Operator 标识
     * @return 授权标识
     */
    public Set<String> authorizationIdsOf(String principalName) {
        Set<String> ids = redis.opsForSet().members(principalKey(principalName));
        return ids == null ? Set.of() : ids;
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        redis.delete(authorizationKey(authorization.getId()));
        tokens(authorization).forEach(token -> redis.delete(tokenKey(token.getTokenValue())));
    }

    @Override
    public OAuth2Authorization findById(String id) {
        String value = redis.opsForValue().get(authorizationKey(id));
        return value == null
                ? null
                : (OAuth2Authorization)
                        serializer.deserialize(Base64.getDecoder().decode(value));
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
        if (OAuth2TokenType.REFRESH_TOKEN.equals(type) && revokeUsedRefreshToken(token)) return null;
        String id = redis.opsForValue().get(tokenKey(token));
        OAuth2Authorization authorization = id == null ? null : findById(id);
        if (authorization == null) return null;
        // 标准 Provider 信任这个类型约束，不能把同一授权中的 code/access_token 当成 refresh_token。
        var matching = type == null
                ? authorization.getToken(token)
                : switch (type.getValue()) {
                    case "code" -> authorization.getToken(OAuth2AuthorizationCode.class);
                    case "access_token" -> authorization.getAccessToken();
                    case "refresh_token" -> authorization.getRefreshToken();
                    default -> null;
                };
        return matching != null && matching.getToken().getTokenValue().equals(token) ? authorization : null;
    }

    public boolean revokeUsedRefreshToken(String token) {
        String id = redis.opsForValue().get(usedRefreshKey(token));
        if (id == null) return false;
        var compromised = findById(id);
        if (compromised != null) remove(compromised);
        return true;
    }

    public static String tokenKey(String token) {
        try {
            return "iam:global:oauth-token:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** 同一授权上换码、刷新与改写的互斥锁，由 {@code TokenGrantLockFilter} 与 {@link MfaCompletionService} 共用。 */
    public static String grantLockKey(String authorizationId) {
        return "iam:global:oauth-grant-lock:" + authorizationId;
    }

    /**
     * 试取互斥锁，不等待。
     *
     * @param key 锁键，如 {@link #grantLockKey}
     * @return 持有者标识，供 {@link #unlock} 使用；锁已被占用时为 null
     */
    public String tryLock(String key) {
        String owner = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, GRANT_LOCK_TTL)) ? owner : null;
    }

    /** 释放 {@link #tryLock} 取得的锁。 */
    public void unlock(String key, String owner) {
        redis.execute(UNLOCK, List.of(key), owner);
    }

    static String usedRefreshKey(String token) {
        return tokenKey(token) + ":used-refresh";
    }

    private static String principalKey(String principalName) {
        return "iam:global:oauth-principal:" + principalName;
    }

    private String authorizationKey(String id) {
        return "iam:global:oauth-authorization:" + id;
    }

    private List<OAuth2Token> tokens(OAuth2Authorization authorization) {
        List<OAuth2Token> result = new ArrayList<>();
        var code = authorization.getToken(OAuth2AuthorizationCode.class);
        if (code != null) result.add(code.getToken());
        if (authorization.getAccessToken() != null)
            result.add(authorization.getAccessToken().getToken());
        if (authorization.getRefreshToken() != null)
            result.add(authorization.getRefreshToken().getToken());
        return result;
    }
}
