package io.saasforge.iam.mfa.config;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MFA 配置，前缀 {@code forge.iam.mfa}。
 *
 * @param encryptionKey 加密 TOTP 密钥的 AES-256 密钥，Base64 编码；不设默认值，多实例必须一致
 */
@ConfigurationProperties("forge.iam.mfa")
public record MfaProperties(String encryptionKey) {
    private static final String PROPERTY = "forge.iam.mfa.encryption-key";
    private static final int KEY_BYTES = 32;

    /** 漏配或换成别的密钥后，已绑定的 Authenticator 全部无法解密，必须在启动时拒绝，而不是等到用户登录时才失败。 */
    public MfaProperties {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException(PROPERTY + " must be set");
        }
        if (decode(encryptionKey).length != KEY_BYTES) {
            throw new IllegalStateException(PROPERTY + " must be a Base64-encoded 256-bit key");
        }
    }

    /** 解码后的 AES-256 密钥。 */
    public byte[] key() {
        return decode(encryptionKey);
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(PROPERTY + " must be a Base64-encoded 256-bit key");
        }
    }

    @Override
    public String toString() {
        return "MfaProperties[redacted]";
    }
}
