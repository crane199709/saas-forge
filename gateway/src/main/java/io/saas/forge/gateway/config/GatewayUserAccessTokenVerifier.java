package io.saas.forge.gateway.config;

import io.saas.forge.sdk.auth.UserAccessTokenInvalidException;
import io.saas.forge.sdk.auth.UserAccessTokenSignatureVerifier;
import java.util.UUID;

/**
 * 验证 IAM 签发的两种 User Access Token 形态，并在签名可信后检查即时撤销状态。
 */
final class GatewayUserAccessTokenVerifier implements GatewayUserTokenVerifier {

    private final UserAccessTokenSignatureVerifier signatures;
    private final GatewayUserTokenRevocationChecker revocations;

    GatewayUserAccessTokenVerifier(
            UserAccessTokenSignatureVerifier signatures,
            GatewayUserTokenRevocationChecker revocations) {
        if (signatures == null || revocations == null) {
            throw new IllegalArgumentException("Gateway User Access Token 校验配置不合法");
        }
        this.signatures = signatures;
        this.revocations = revocations;
    }

    @Override
    public void verify(String authorization) {
        try {
            var claims = signatures.verify(authorization);
            revocations.assertAllowed(claims.jti(), claims.kid(), claims.membershipId(), claims.tenantId(),
                    claims.identityId());
        } catch (GatewayTokenRevocationStatusUnavailableException exception) {
            throw exception;
        } catch (GatewayUserTokenInvalidException exception) {
            throw exception;
        } catch (UserAccessTokenInvalidException exception) {
            throw new GatewayUserTokenInvalidException(exception);
        }
    }
}

@FunctionalInterface
interface GatewayUserTokenVerifier {
    void verify(String authorization);
}

@FunctionalInterface
interface GatewayUserTokenRevocationChecker {
    void assertAllowed(UUID jti, String kid, UUID membershipId, UUID tenantId);

    default void assertAllowed(UUID jti, String kid, UUID membershipId, UUID tenantId, UUID identityId) {
        assertAllowed(jti, kid, membershipId, tenantId);
    }
}

final class GatewayUserTokenInvalidException extends RuntimeException {
    GatewayUserTokenInvalidException() {
        super();
    }

    GatewayUserTokenInvalidException(Throwable cause) {
        super(cause);
    }
}

final class GatewayTokenRevocationStatusUnavailableException extends RuntimeException {
    GatewayTokenRevocationStatusUnavailableException() {
        super();
    }

    GatewayTokenRevocationStatusUnavailableException(Throwable cause) {
        super(cause);
    }
}
