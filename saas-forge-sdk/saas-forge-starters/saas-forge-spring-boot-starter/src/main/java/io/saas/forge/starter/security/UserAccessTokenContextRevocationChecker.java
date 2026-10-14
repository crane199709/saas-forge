package io.saas.forge.starter.security;

import java.util.UUID;

/**
 * 同时检查 User Token 的 jti、kid 与可选 Membership/Tenant Fence；Ready 或状态不可判定时抛出异常。
 */
@FunctionalInterface
public interface UserAccessTokenContextRevocationChecker {
    boolean isRevoked(UUID jti, String kid, UUID membershipId, UUID tenantId);

    default boolean isRevoked(UUID jti, String kid, UUID membershipId, UUID tenantId, UUID identityId) {
        return isRevoked(jti, kid, membershipId, tenantId);
    }
}
