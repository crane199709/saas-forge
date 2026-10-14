package io.saas.forge.starter.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 按 IAM Published Redis Key 同时复验 Ready、Token/Client、Signing Key 与 User Fence。 */
final class RedisReceiverTokenRevocationChecker {
    private final StringRedisTemplate redis;
    private final String prefix;

    RedisReceiverTokenRevocationChecker(StringRedisTemplate redis, String environment) {
        if (environment == null || environment.isBlank()) {
            throw new IllegalArgumentException("接收端 Redis Key environment 不能为空");
        }
        this.redis = redis;
        this.prefix = "sf:" + environment + ":iam-service:";
    }

    boolean isServiceTokenRevoked(UUID clientId, String kid) {
        return hasRevocation(List.of(
                prefix + "oauth-client-revocation:v1:" + clientId,
                prefix + "signing-kid-revocation:v1:" + digest(kid)));
    }

    boolean isUserTokenRevoked(UUID jti, String kid, UUID membershipId, UUID tenantId) {
        return isUserTokenRevoked(jti, kid, membershipId, tenantId, null);
    }

    boolean isUserTokenRevoked(UUID jti, String kid, UUID membershipId, UUID tenantId, UUID identityId) {
        List<String> keys = new ArrayList<>();
        keys.add(prefix + "jwt-jti-revocation:v1:" + digest(jti.toString()));
        keys.add(prefix + "signing-kid-revocation:v1:" + digest(kid));
        if (identityId != null) {
            keys.add(prefix + "user-session-revocation-fence:v1:identity:" + identityId);
        }
        if (membershipId != null) {
            keys.add(prefix + "user-session-revocation-fence:v1:tenant:" + tenantId);
            keys.add(prefix + "user-session-revocation-fence:v1:membership:" + membershipId);
        }
        return hasRevocation(keys);
    }

    boolean isReady() {
        return "1".equals(redis.opsForValue().get(prefix + "revocation-index-ready:v1:state"));
    }

    private boolean hasRevocation(List<String> revocationKeys) {
        List<String> keys = new ArrayList<>(revocationKeys.size() + 1);
        keys.add(prefix + "revocation-index-ready:v1:state");
        keys.addAll(revocationKeys);
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null || values.size() != keys.size() || !"1".equals(values.get(0))) {
            throw new IllegalStateException("IAM 撤销索引不可用");
        }
        return values.subList(1, values.size()).stream().anyMatch(value -> value != null);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }
}
