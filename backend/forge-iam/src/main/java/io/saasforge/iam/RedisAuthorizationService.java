package io.saasforge.iam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Service;

@Service
class RedisAuthorizationService implements OAuth2AuthorizationService {
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
                    redis.delete(tokenKey(old.getTokenValue()));
                }
            }
        }
        redis.opsForValue()
                .set(
                        authorizationKey(authorization.getId()),
                        Base64.getEncoder().encodeToString(serializer.serialize(authorization)),
                        ttl);
        for (OAuth2Token token : tokens) {
            Duration tokenTtl = Duration.between(Instant.now(), token.getExpiresAt());
            if (!tokenTtl.isNegative() && !tokenTtl.isZero()) {
                redis.opsForValue().set(tokenKey(token.getTokenValue()), authorization.getId(), tokenTtl);
            }
        }
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

    static String tokenKey(String token) {
        try {
            return "iam:global:oauth-token:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
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
