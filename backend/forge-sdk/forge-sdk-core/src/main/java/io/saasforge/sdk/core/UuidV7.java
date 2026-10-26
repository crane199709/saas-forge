package io.saasforge.sdk.core;

import java.security.SecureRandom;
import java.util.UUID;

/** 按 RFC 9562 生成 UUIDv7 主键：无需多实例协调，且按毫秒时间有序，对索引友好（ADR-0014）。 */
public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    /**
     * 生成新的 UUIDv7。
     *
     * @return 高 48 位为 Unix 毫秒时间戳、其余为随机位的 UUID
     */
    public static UUID generate() {
        long millis = System.currentTimeMillis();
        long mostSignificant = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long leastSignificant = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
