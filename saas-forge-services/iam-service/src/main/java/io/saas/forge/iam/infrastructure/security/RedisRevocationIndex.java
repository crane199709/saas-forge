package io.saas.forge.iam.infrastructure.security;

import io.saas.forge.iam.application.authentication.RevocationIndex;
import io.saas.forge.iam.application.authentication.RevocationIndexUnavailableException;
import io.saas.forge.iam.application.authentication.RevocationFenceConflictException;
import io.saas.forge.iam.domain.session.DurableRevocation;
import io.saas.forge.iam.domain.session.AccessTokenIssuance;
import io.saas.forge.iam.domain.session.RevocationFence;
import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisRevocationIndex implements RevocationIndex {
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final DefaultRedisScript<Long> UPSERT = new DefaultRedisScript<>("""
            local current = redis.call('PTTL', KEYS[1])
            local requested = tonumber(ARGV[1])
            if current < requested then redis.call('SET', KEYS[1], '1', 'PX', requested) end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> UPSERT_MANY = new DefaultRedisScript<>("""
            for index, key in ipairs(KEYS) do
                local current = redis.call('PTTL', key)
                local requested = tonumber(ARGV[index])
                if current < requested then redis.call('SET', key, '1', 'PX', requested) end
            end
            return #KEYS
            """, Long.class);
    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= '1' then return -1 end
            if redis.call('EXISTS', KEYS[2]) == 1 then return 1 end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> CHECK_TOKEN = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= '1' then return -1 end
            if redis.call('EXISTS', KEYS[2]) == 1 or redis.call('EXISTS', KEYS[3]) == 1 then return 1 end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> ESTABLISH_FENCE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= '1' then return -1 end
            local current = redis.call('GET', KEYS[2])
            if current and current ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[1])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> CHECK_FENCE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= '1' then return -1 end
            if redis.call('EXISTS', KEYS[2]) == 1 then return 1 end
            if redis.call('EXISTS', KEYS[3]) == 1 then return 1 end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_FENCE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= '1' then return -1 end
            local current = redis.call('GET', KEYS[2])
            if not current then return 1 end
            if current ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;
    private final String readyKey;
    private volatile boolean recoveryRequired;

    public RedisRevocationIndex(StringRedisTemplate redis, String environment) {
        this.redis = redis;
        this.prefix = "sf:" + environment + ":iam-service:";
        this.readyKey = prefix + "revocation-index-ready:v1:state";
    }

    @Override
    public void revokeJti(UUID jti, Instant expiresAt, Instant at) {
        try {
            write(jtiKey(jti), expiresAt.plus(CLOCK_SKEW), at);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void revokeClient(UUID clientId) {
        try {
            redis.opsForValue().set(clientKey(clientId), "1");
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void revokeSigningKey(
            String kid, Instant rejectUntil, List<AccessTokenIssuance> issuances, Instant at) {
        if (kid == null || kid.isBlank() || rejectUntil == null || issuances == null || at == null) {
            throw new IllegalArgumentException("Signing Key 撤销索引参数不完整");
        }
        List<String> keys = new java.util.ArrayList<>();
        List<String> ttlMillis = new java.util.ArrayList<>();
        keys.add(kidKey(kid));
        ttlMillis.add(ttlMillis(rejectUntil, at));
        for (AccessTokenIssuance issuance : issuances) {
            keys.add(jtiKey(issuance.jti()));
            ttlMillis.add(ttlMillis(issuance.expiresAt().plus(CLOCK_SKEW), at));
        }
        try {
            Long written = redis.execute(UPSERT_MANY, keys, ttlMillis.toArray());
            if (written == null || written != keys.size()) {
                throw new RevocationIndexUnavailableException();
            }
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void markNotReady() {
        recoveryRequired = true;
        try {
            redis.opsForValue().set(readyKey, "0");
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void rebuild(
            List<DurableRevocation> revocations,
            List<RevocationFence> fences,
            List<UUID> revokedClientIds,
            Instant at) {
        try {
            for (DurableRevocation revocation : revocations) {
                if (revocation.jtiRevoked()) {
                    write(jtiKey(revocation.jti()), revocation.expiresAt().plus(CLOCK_SKEW), at);
                }
            }
            Map<String, Instant> kidExpiries = revocations.stream()
                    .filter(DurableRevocation::kidRevoked)
                    .collect(Collectors.toMap(
                            DurableRevocation::kid,
                            DurableRevocation::expiresAt,
                            (left, right) -> Comparator.<Instant>naturalOrder().compare(left, right) >= 0 ? left : right));
            kidExpiries.forEach((kid, expiresAt) -> write(kidKey(kid), expiresAt.plus(CLOCK_SKEW), at));
            for (RevocationFence fence : fences) {
                redis.opsForValue().set(fenceKey(fence.target()), fence.revocationRequestId().toString());
            }
            var authoritativeClientKeys = revokedClientIds.stream()
                    .map(this::clientKey)
                    .collect(java.util.stream.Collectors.toSet());
            for (UUID clientId : revokedClientIds) {
                redis.opsForValue().set(clientKey(clientId), "1");
            }
            try (var keys = redis.scan(ScanOptions.scanOptions()
                    .match(prefix + "oauth-client-revocation:v1:*").count(1000).build())) {
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!authoritativeClientKeys.contains(key)) redis.delete(key);
                }
            }
            redis.opsForValue().set(readyKey, "1");
            recoveryRequired = false;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void establishFence(RevocationFence fence) {
        try {
            Long result = redis.execute(
                    ESTABLISH_FENCE,
                    List.of(readyKey, fenceKey(fence.target())),
                    fence.revocationRequestId().toString());
            if (result == null || result < 0) {
                throw new RevocationIndexUnavailableException();
            }
            if (result == 0) {
                throw new RevocationFenceConflictException();
            }
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean releaseFence(RevocationFence fence) {
        try {
            Long result = redis.execute(
                    RELEASE_FENCE,
                    List.of(readyKey, fenceKey(fence.target())),
                    fence.revocationRequestId().toString());
            if (result == null || result < 0) {
                throw new RevocationIndexUnavailableException();
            }
            return result == 1;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean isUserTokenFenced(RevocationFenceTarget target) {
        if (target.tenantId() == null) {
            return isIdentityFenced(target.identityId());
        }
        try {
            Long result = redis.execute(CHECK_FENCE, List.of(
                    readyKey,
                    fenceKey(RevocationFenceTarget.tenant(target.tenantId())),
                    fenceKey(target)));
            if (result == null || result < 0) {
                throw new RevocationIndexUnavailableException();
            }
            return result == 1;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean isIdentityFenced(UUID identityId) {
        try {
            Long result = redis.execute(CHECK, List.of(readyKey,
                    fenceKey(RevocationFenceTarget.identity(identityId))));
            if (result == null || result < 0) {
                throw new RevocationIndexUnavailableException();
            }
            return result == 1;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean isJtiRevoked(UUID jti) {
        return isRevoked(jtiKey(jti));
    }

    @Override
    public boolean isKidRevoked(String kid) {
        return isRevoked(kidKey(kid));
    }

    @Override
    public boolean isClientRevoked(UUID clientId) {
        return isRevoked(clientKey(clientId));
    }

    @Override
    public boolean isServiceTokenRevoked(UUID clientId, String kid) {
        return isAnyRevoked(clientKey(clientId), kidKey(kid));
    }

    @Override
    public boolean isTokenRevoked(UUID jti, String kid) {
        return isAnyRevoked(jtiKey(jti), kidKey(kid));
    }

    private boolean isAnyRevoked(String firstRevocationKey, String secondRevocationKey) {
        if (recoveryRequired) {
            throw new RevocationIndexUnavailableException();
        }
        try {
            Long result = redis.execute(CHECK_TOKEN, List.of(
                    readyKey, firstRevocationKey, secondRevocationKey));
            if (result == null || result < 0) {
                throw new RevocationIndexUnavailableException();
            }
            return result == 1;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private boolean isRevoked(String revocationKey) {
        try {
            Long result = redis.execute(CHECK, List.of(readyKey, revocationKey));
            if (result == null || result < 0) {
                throw new RevocationIndexUnavailableException();
            }
            return result == 1;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean isReady() {
        try {
            return !recoveryRequired && "1".equals(redis.opsForValue().get(readyKey));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void write(String key, Instant rejectUntil, Instant at) {
        redis.execute(UPSERT, List.of(key), ttlMillis(rejectUntil, at));
    }

    private static String ttlMillis(Instant rejectUntil, Instant at) {
        return Long.toString(Math.max(1, Duration.between(at, rejectUntil).toMillis()));
    }

    private String jtiKey(UUID jti) {
        return prefix + "jwt-jti-revocation:v1:" + digest(jti.toString());
    }

    private String kidKey(String kid) {
        return prefix + "signing-kid-revocation:v1:" + digest(kid);
    }

    private String clientKey(UUID clientId) {
        return prefix + "oauth-client-revocation:v1:" + clientId;
    }

    private String fenceKey(RevocationFenceTarget target) {
        return prefix + "user-session-revocation-fence:v1:"
                + target.type().keySegment() + ":" + target.targetId();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }

    private RevocationIndexUnavailableException unavailable(DataAccessException exception) {
        recoveryRequired = true;
        return new RevocationIndexUnavailableException(exception);
    }
}
