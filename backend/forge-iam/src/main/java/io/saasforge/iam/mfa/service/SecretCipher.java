package io.saasforge.iam.mfa.service;

import io.saasforge.iam.mfa.config.MfaProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 以 AES-256-GCM 加密 TOTP 密钥，Identity 与 Operator 的 Authenticator 共用。
 *
 * <p>密文格式为 12 字节随机 nonce 加密文与认证标签。{@code owner} 作为附加认证数据：把一行密文挪到另一个账号名下会解密失败，
 * 拿到数据库写权限的人也无法让别人的 Authenticator 为自己所用。
 */
@Component
public class SecretCipher {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    SecretCipher(MfaProperties properties) {
        this.key = new SecretKeySpec(properties.key(), "AES");
    }

    /**
     * 加密密钥。
     *
     * @param owner 密钥所属账号，如 {@code identity:<id>}；解密时必须相同
     */
    public byte[] encrypt(byte[] secret, String owner) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(secret);
            return ByteBuffer.allocate(NONCE_BYTES + sealed.length)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    /** 解密密钥；密文被篡改、属于其他账号或加密密钥已更换时抛出 {@link IllegalStateException}。 */
    public byte[] decrypt(byte[] ciphertext, String owner) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, ciphertext, 0, NONCE_BYTES));
            cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(ciphertext, NONCE_BYTES, ciphertext.length - NONCE_BYTES);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Authenticator secret cannot be decrypted", error);
        }
    }
}
