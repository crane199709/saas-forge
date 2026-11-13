package io.saasforge.iam.lockout.service;

import io.saasforge.iam.clientip.service.ClientIpResolver;
import io.saasforge.iam.lockout.config.LockoutProperties;
import io.saasforge.iam.lockout.model.LockoutTarget;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.api.ForgeException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Login Lockout（账号安全规格第 3 节）：认证失败按 {@link LockoutTarget} 与客户端 IP 计数，窗口内达到阈值即临时锁定，锁定期内拒绝该对象的全部认证，
 * 到期自动解除。计数与锁定只在 Redis，以键的 TTL 表达窗口与锁定时长。
 *
 * <p>调用方在校验凭证前调用 {@link #check}，凭证错误时调用 {@link #failed}，原样抛出原错误；达到阈值的那次失败仍返回原错误，之后的请求才返回
 * {@code auth.locked}。
 */
@Service
public class LoginLockoutService {
    /** 触发 Login Lockout 的审计动作。 */
    public static final String LOCKED = "auth.locked";

    // 认证前没有可信的租户标识，与验证码一样使用 iam:global: 前缀。
    private static final String PREFIX = "iam:global:lockout:";
    // 计数与锁定必须原子，否则并发猜测可越过阈值。返回 1 表示本次失败触发锁定。
    private static final DefaultRedisScript<Long> FAIL = new DefaultRedisScript<>(
            "local count = redis.call('incr', KEYS[1]) "
                    + "if count == 1 then redis.call('pexpire', KEYS[1], ARGV[2]) end "
                    + "if count >= tonumber(ARGV[1]) then "
                    + "redis.call('del', KEYS[1]) redis.call('set', KEYS[2], '1', 'PX', ARGV[3]) return 1 end "
                    + "return 0",
            Long.class);

    private final StringRedisTemplate redis;
    private final AuditRecorder audits;
    private final LockoutProperties properties;

    LoginLockoutService(StringRedisTemplate redis, AuditRecorder audits, LockoutProperties properties) {
        this.redis = redis;
        this.audits = audits;
        this.properties = properties;
    }

    /**
     * 对象或当前客户端 IP 处于锁定期时拒绝认证。
     *
     * @throws ForgeException 429 {@code auth.locked}，并写 {@code Retry-After} 响应头（剩余秒数）
     */
    public void check(LockoutTarget target) {
        long remaining = remainingMillis(lockedKey(target.realm(), target.key()));
        String ip = ClientIpResolver.currentIp();
        if (ip != null) {
            remaining = Math.max(remaining, remainingMillis(lockedKey(target.realm(), ipKey(ip))));
        }
        if (remaining <= 0) {
            return;
        }
        long seconds = Math.max(1, (remaining + 999) / 1000);
        HttpServletResponse response = currentResponse();
        if (response != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        }
        throw ForgeException.withErrorMessage(
                LockoutErrorCode.LOCKED,
                "Too many failed attempts, retry after " + seconds + "s",
                // 以文本传入：数字参数会按语言加千位分隔符。
                String.valueOf(seconds));
    }

    /** 记一次失败：对象与当前客户端 IP 各计一次，达到阈值即锁定并写审计。 */
    public void failed(LockoutTarget target) {
        LockoutProperties.Realm rule = rule(target.realm());
        if (count(target.realm(), target.key(), rule.maxFailures(), rule)) {
            record(target.realm(), target.actor(), "account", target.account(), rule);
        }
        String ip = ClientIpResolver.currentIp();
        if (ip != null && count(target.realm(), ipKey(ip), rule.ipMaxFailures(), rule)) {
            record(target.realm(), null, "ip", ip, rule);
        }
    }

    /** 认证通过后清零该对象的失败计数；IP 计数不清零，否则攻击者可夹带自己的正确凭证绕过 IP 限速。 */
    public void succeeded(LockoutTarget target) {
        redis.delete(failuresKey(target.realm(), target.key()));
    }

    private boolean count(AuditRealm realm, String key, int max, LockoutProperties.Realm rule) {
        Long locked = redis.execute(
                FAIL,
                List.of(failuresKey(realm, key), lockedKey(realm, key)),
                String.valueOf(max),
                String.valueOf(rule.window().toMillis()),
                String.valueOf(rule.lockDuration().toMillis()));
        return locked != null && locked == 1;
    }

    private void record(AuditRealm realm, UUID actor, String detail, String value, LockoutProperties.Realm rule) {
        var event = AuditEvent.builder(realm, AuditCategory.LOGIN, LOCKED)
                .actor(actor)
                .detail("lockSeconds", rule.lockDuration().toSeconds());
        if (value != null) {
            event.detail(detail, value);
        }
        audits.record(event.build());
    }

    private long remainingMillis(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        return ttl == null ? 0 : ttl;
    }

    private LockoutProperties.Realm rule(AuditRealm realm) {
        return realm == AuditRealm.PLATFORM ? properties.platform() : properties.tenant();
    }

    private static String failuresKey(AuditRealm realm, String key) {
        return PREFIX + realm.name().toLowerCase(Locale.ROOT) + ":failures:" + key;
    }

    private static String lockedKey(AuditRealm realm, String key) {
        return PREFIX + realm.name().toLowerCase(Locale.ROOT) + ":locked:" + key;
    }

    private static String ipKey(String ip) {
        return "ip:" + ip;
    }

    private static HttpServletResponse currentResponse() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getResponse()
                : null;
    }
}
