package io.saasforge.iam.mfa.model;

import java.util.UUID;

/**
 * 已绑定 Authenticator 的查询投影，带密钥密文，禁止直接作为接口响应。
 *
 * @param lastUsedStep 上次通过校验的 TOTP 时间步
 */
public record StoredAuthenticator(UUID id, byte[] secretCiphertext, long lastUsedStep) {
    @Override
    public String toString() {
        return "StoredAuthenticator[id=" + id + "]";
    }
}
