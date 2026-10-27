package io.saasforge.iam.security.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

/** Platform Realm 的签名私钥；公钥经 {@link #publicKeys()} 由 Platform Realm 的 JWKS 端点发布。 */
public record PlatformSigningKey(RSAKey key) {
    public JWKSet publicKeys() {
        return new JWKSet(key.toPublicJWK());
    }

    @Override
    public String toString() {
        return "PlatformSigningKey[redacted]";
    }
}
