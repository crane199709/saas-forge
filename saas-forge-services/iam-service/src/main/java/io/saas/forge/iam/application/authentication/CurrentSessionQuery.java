package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.application.authorization.PlatformRoleAuthorizationService;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.sdk.auth.UserAccessTokenInvalidException;
import io.saas.forge.sdk.auth.UserAccessTokenSignatureVerifier;
import java.util.UUID;

/** 当前请求的 IAM 权威快照，不查询 Refresh Family，也不替代业务 operation 授权。 */
public final class CurrentSessionQuery {
    private final UserAccessTokenSignatureVerifier signatures;
    private final RevocationIndex revocations;
    private final IdentityRepository identities;
    private final PlatformRoleAuthorizationService roles;

    public CurrentSessionQuery(UserAccessTokenSignatureVerifier signatures, RevocationIndex revocations,
            IdentityRepository identities, PlatformRoleAuthorizationService roles) {
        this.signatures = signatures;
        this.revocations = revocations;
        this.identities = identities;
        this.roles = roles;
    }

    public Snapshot read(String authorization) {
        var claims = signatures.verify(authorization);
        try {
            if (revocations.isTokenRevoked(claims.jti(), claims.kid())
                    || revocations.isIdentityFenced(claims.identityId())) {
                throw new UserAccessTokenInvalidException();
            }
        } catch (RevocationIndexUnavailableException unavailable) {
            throw new TokenRevocationStatusUnavailableException();
        }
        if (claims.membershipId() != null || claims.tenantId() != null) {
            throw new AccessContextUnavailableException();
        }
        var identity = identities.findById(claims.identityId()).orElseThrow(UserAccessTokenInvalidException::new);
        return new Snapshot(identity.id(), identity.email().value(), identity.displayName(),
                roles.isAllowed(identity.id(), "PLATFORM_ADMIN"));
    }

    public record Snapshot(UUID identityId, String email, String displayName, boolean platformAdmin) {}
}
