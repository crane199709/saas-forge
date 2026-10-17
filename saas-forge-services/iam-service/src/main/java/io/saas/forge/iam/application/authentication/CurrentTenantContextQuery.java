package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import io.saas.forge.sdk.auth.UserAccessTokenInvalidException;
import io.saas.forge.sdk.auth.UserAccessTokenSignatureVerifier;

/** 从已验证 Token 的当前 Membership 读取快照，不消费或轮换浏览器会话。 */
public final class CurrentTenantContextQuery {
    private final UserAccessTokenSignatureVerifier signatures;
    private final AccessibleMemberships memberships;
    private final RevocationIndex revocations;

    public CurrentTenantContextQuery(
            UserAccessTokenSignatureVerifier signatures, AccessibleMemberships memberships,
            RevocationIndex revocations) {
        this.signatures = signatures;
        this.memberships = memberships;
        this.revocations = revocations;
    }

    public TenantAuthenticationContextSnapshot read(String authorization) {
        var claims = signatures.verify(authorization);
        if (claims.membershipId() == null || claims.tenantId() == null) {
            throw new AccessContextUnavailableException();
        }
        try {
            if (revocations.isTokenRevoked(claims.jti(), claims.kid())
                    || revocations.isIdentityFenced(claims.identityId())) {
                throw new UserAccessTokenInvalidException();
            }
            if (revocations.isUserTokenFenced(
                    RevocationFenceTarget.membership(claims.membershipId(), claims.tenantId()))) {
                throw new AccessContextUnavailableException();
            }
        } catch (RevocationIndexUnavailableException unavailable) {
            // 读取不可判定不等于会话已失效；只读接口不得清除任何槽位 Cookie。
            throw new TokenRevocationStatusUnavailableException();
        }
        var accessible = memberships.findByIdentityId(claims.identityId());
        var current = accessible.stream()
                .filter(membership -> membership.membershipId().equals(claims.membershipId())
                        && membership.tenantId().equals(claims.tenantId()))
                .findFirst().orElseThrow(AccessContextUnavailableException::new);
        return new TenantAuthenticationContextSnapshot(current, accessible);
    }
}
