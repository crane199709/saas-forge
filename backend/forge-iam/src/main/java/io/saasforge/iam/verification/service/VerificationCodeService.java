package io.saasforge.iam.verification.service;

import io.saasforge.iam.verification.config.VerificationProperties;
import io.saasforge.iam.verification.model.CodeDelivery;
import io.saasforge.sdk.core.api.ForgeException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 签发与校验发往邮箱或手机的一次性验证码，状态只在 Redis：有效期即键的 TTL，发送间隔按目标限制、不区分用途，
 * 错误次数达到上限即作废。
 *
 * <p>验证码明文存放：6 位数字的取值空间很小，哈希挡不住拿到 Redis 数据的人，防猜测靠错误次数上限。
 */
@Service
public class VerificationCodeService {
    // 认证前没有可信的租户标识，键沿用 iam:global: 前缀。
    private static final String PREFIX = "iam:global:verification:";
    // 覆盖旧码时重置错误次数；返回 0 表示仍在发送间隔内。
    private static final DefaultRedisScript<Long> ISSUE = new DefaultRedisScript<>(
            "if redis.call('set', KEYS[1], '1', 'PX', ARGV[3], 'NX') == false then "
                    + "return -redis.call('pttl', KEYS[1]) end "
                    + "redis.call('del', KEYS[2]) "
                    + "redis.call('hset', KEYS[2], 'code', ARGV[1], 'attempts', 0) "
                    + "redis.call('pexpire', KEYS[2], ARGV[2]) return 1",
            Long.class);
    // 0 不存在，1 通过（同时作废），2 错误，3 错误且达到上限（同时作废）。比较与计数必须原子，否则并发猜测可绕过上限。
    private static final DefaultRedisScript<Long> VERIFY = new DefaultRedisScript<>(
            "local stored = redis.call('hget', KEYS[1], 'code') "
                    + "if not stored then return 0 end "
                    + "if stored == ARGV[1] then redis.call('del', KEYS[1]) return 1 end "
                    + "if redis.call('hincrby', KEYS[1], 'attempts', 1) >= tonumber(ARGV[2]) then "
                    + "redis.call('del', KEYS[1]) return 3 end "
                    + "return 2",
            Long.class);

    private final StringRedisTemplate redis;
    private final SmsSender sms;
    private final EmailSender email;
    private final Duration ttl;
    private final Duration resendInterval;
    private final int maxAttempts;
    private final SecureRandom random = new SecureRandom();

    VerificationCodeService(
            StringRedisTemplate redis,
            ObjectProvider<SmsSender> sms,
            ObjectProvider<EmailSender> email,
            VerificationProperties properties) {
        this.redis = redis;
        // 用 ObjectProvider 而不是条件 Bean：普通配置类中的 @ConditionalOnMissingBean 受注册顺序影响，可能与部署方的实现并存。
        this.sms = sms.getIfUnique(() -> new LoggingCodeSender("sms"));
        this.email = email.getIfUnique(() -> new LoggingCodeSender("email"));
        this.ttl = properties.codeTtl();
        this.resendInterval = properties.resendInterval();
        this.maxAttempts = properties.maxAttempts();
    }

    /**
     * 为目标生成新验证码，覆盖同用途的旧码。{@code deliver} 为 false 时照常生成并计入发送间隔但不发送，
     * 使调用方对不存在的登录目标给出相同的响应与校验结果。
     */
    public CodeDelivery issue(VerificationPurpose purpose, String target, boolean deliver) {
        String code = "%06d".formatted(random.nextInt(1_000_000));
        String normalized = normalize(target);
        List<String> keys = List.of(PREFIX + "cooldown:" + normalized, codeKey(purpose, normalized));
        Long result = redis.execute(
                ISSUE, keys, code, String.valueOf(ttl.toMillis()), String.valueOf(resendInterval.toMillis()));
        if (result == null || result <= 0) {
            long remaining = result == null ? resendInterval.toSeconds() : (-result + 999) / 1000;
            throw ForgeException.withErrorMessage(
                    VerificationErrorCode.TOO_FREQUENT, "Verification code requested too frequently", remaining);
        }
        if (deliver) {
            var message = new VerificationMessage(purpose, code, ttl);
            try {
                if (isEmail(target)) email.send(target, message);
                else sms.send(target, message);
            } catch (RuntimeException error) {
                // 没发出去的码不应占用发送间隔，否则用户要等满间隔才能重试。
                redis.delete(keys);
                throw error;
            }
        }
        return new CodeDelivery(ttl.toSeconds(), resendInterval.toSeconds());
    }

    /** 校验并消费验证码；不通过时抛出对应的 {@link VerificationErrorCode}。 */
    public void verify(VerificationPurpose purpose, String target, String code) {
        Long result =
                redis.execute(VERIFY, List.of(codeKey(purpose, normalize(target))), code, String.valueOf(maxAttempts));
        VerificationErrorCode error = switch (result == null ? 0 : result.intValue()) {
            case 1 -> null;
            case 2 -> VerificationErrorCode.CODE_INVALID;
            case 3 -> VerificationErrorCode.ATTEMPTS_EXCEEDED;
            default -> VerificationErrorCode.CODE_EXPIRED;
        };
        if (error != null) throw ForgeException.withErrorMessage(error, "Verification code rejected");
    }

    public static boolean isEmail(String target) {
        return target.contains("@");
    }

    // 邮箱不区分大小写，与身份表的唯一性规则一致。
    private static String normalize(String target) {
        return isEmail(target) ? target.toLowerCase(Locale.ROOT) : target;
    }

    private static String codeKey(VerificationPurpose purpose, String normalized) {
        return PREFIX + "code:" + purpose.name().toLowerCase(Locale.ROOT) + ":" + normalized;
    }
}
