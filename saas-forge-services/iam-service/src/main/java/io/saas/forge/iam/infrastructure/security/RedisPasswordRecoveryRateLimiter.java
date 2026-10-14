package io.saas.forge.iam.infrastructure.security;

import io.saas.forge.iam.domain.identity.NormalizedEmail;
import io.saas.forge.iam.application.authentication.AuthenticationProtectionUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** 同一邮箱的公开恢复请求在固定窗口内限额；键不包含原始邮箱。 */
public final class RedisPasswordRecoveryRateLimiter {
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);
    private final StringRedisTemplate redis;
    private final String prefix;
    private final int maximum;
    private final Duration window;

    public RedisPasswordRecoveryRateLimiter(StringRedisTemplate redis, String environment,
            int maximum, Duration window) {
        if (maximum < 1 || window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Password Recovery 限流策略不合法");
        }
        this.redis = redis;
        this.prefix = "sf:" + environment + ":iam-service:password-recovery-request:v1:";
        this.maximum = maximum;
        this.window = window;
    }

    public boolean allow(NormalizedEmail email) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(email.value().getBytes(StandardCharsets.UTF_8));
            Long count = redis.execute(COUNT, List.of(prefix + HexFormat.of().formatHex(hash)),
                    Long.toString(window.toMillis()));
            if (count == null) throw new IllegalStateException("Password Recovery 限流状态不可判定");
            return count <= maximum;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        } catch (DataAccessException exception) {
            throw new AuthenticationProtectionUnavailableException(exception);
        }
    }
}
