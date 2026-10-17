package io.saas.forge.gateway.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 构造 Gateway 读取的 IAM Redis Revocation Index 已登记 Key。 */
final class GatewayIamRevocationRedisKeys {
    private final String prefix;

    GatewayIamRevocationRedisKeys(String environment) {
        if (environment == null || environment.isBlank()) {
            throw new IllegalArgumentException("Gateway Redis Key environment 不能为空");
        }
        this.prefix = "sf:" + environment + ":iam-service:";
    }

    String ready() {
        return prefix + "revocation-index-ready:v1:state";
    }

    String jti(UUID jti) {
        return prefix + "jwt-jti-revocation:v1:" + digest(jti.toString());
    }

    String kid(String kid) {
        return prefix + "signing-kid-revocation:v1:" + digest(kid);
    }

    String oauthClient(UUID clientId) {
        return prefix + "oauth-client-revocation:v1:" + clientId;
    }

    String tenantFence(UUID tenantId) {
        return prefix + "user-session-revocation-fence:v1:tenant:" + tenantId;
    }

    String membershipFence(UUID membershipId) {
        return prefix + "user-session-revocation-fence:v1:membership:" + membershipId;
    }

    String identityFence(UUID identityId) {
        return prefix + "user-session-revocation-fence:v1:identity:" + identityId;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }
}
