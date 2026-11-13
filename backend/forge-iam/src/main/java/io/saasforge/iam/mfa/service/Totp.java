package io.saasforge.iam.mfa.service;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP，参数固定为 SHA-1、6 位、30 秒步长：这是各验证器 App 普遍支持的组合，改动会让已绑定的 Authenticator 全部失效。
 *
 * <p>Identity 与 Operator 的 Authenticator 共用此实现；防重放所需的「上次使用的步长」由调用方持久化并传入。
 */
public final class Totp {
    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;
    // 前后各容忍 1 个步长的时钟误差。
    private static final int DRIFT_STEPS = 1;
    private static final int SECRET_BYTES = 20;
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** 生成新的 160 位密钥，与 SHA-1 的输出长度一致（RFC 4226 推荐）。 */
    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** 某一时刻所在的时间步。 */
    public static long step(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS);
    }

    /** 某一时间步的动态码。 */
    public static String code(byte[] secret, long step) {
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return "%06d".formatted(binary % 1_000_000);
    }

    /**
     * 在 {@code now} 前后各 1 个步长内查找与 {@code code} 匹配、且晚于 {@code lastUsedStep} 的时间步。
     *
     * <p>只接受晚于上次使用的步长，同一动态码因此在有效期内只能用一次，更早步长的码也不能再用（RFC 6238 §5.2）。
     *
     * @param lastUsedStep 上次通过校验的时间步；从未使用时传负数
     * @return 匹配的时间步，调用方应将其持久化为新的 {@code lastUsedStep}；不匹配时为空
     */
    public static OptionalLong match(byte[] secret, String code, Instant now, long lastUsedStep) {
        if (code == null || code.length() != DIGITS || !code.chars().allMatch(Character::isDigit)) {
            return OptionalLong.empty();
        }
        long current = step(now);
        byte[] submitted = code.getBytes(StandardCharsets.US_ASCII);
        for (long step = current - DRIFT_STEPS; step <= current + DRIFT_STEPS; step++) {
            if (step <= lastUsedStep) {
                continue;
            }
            // 定长比较，不按首个不同字符提前返回。
            if (MessageDigest.isEqual(submitted, code(secret, step).getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /**
     * 供验证器 App 扫码的 {@code otpauth://} URI（Key Uri Format）。
     *
     * @param issuer 在验证器 App 中显示的服务名
     * @param accountName 在验证器 App 中区分账号的名称
     */
    public static String otpauthUri(String issuer, String accountName, byte[] secret) {
        String label = encode(issuer) + ":" + encode(accountName);
        return "otpauth://totp/" + label + "?secret=" + base32(secret) + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    // URI 路径与查询中的空格写作 %20：部分验证器 App 不把 + 解码为空格。
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // RFC 4648 Base32，不带填充，验证器 App 的通行写法。
    private static String base32(byte[] data) {
        var out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }
}
