package io.saasforge.sdk.scheduling;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

final class RedisTaskLock {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final String service;

    RedisTaskLock(StringRedisTemplate redis, String service) {
        this.redis = redis;
        this.service = service;
    }

    boolean acquire(String name, String token) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(name), token, LEASE));
    }

    boolean renew(String name, String token) {
        return Long.valueOf(1).equals(redis.execute(RENEW, List.of(key(name)), token, Long.toString(LEASE.toMillis())));
    }

    void release(String name, String token) {
        redis.execute(RELEASE, List.of(key(name)), token);
    }

    private String key(String name) {
        return "forge:task:" + service + ":" + name;
    }
}
