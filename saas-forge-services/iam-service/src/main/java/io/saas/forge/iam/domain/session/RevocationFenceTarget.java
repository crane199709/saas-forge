package io.saas.forge.iam.domain.session;

import java.util.UUID;

/** 强类型 Fence 目标；Membership 目标必须同时绑定所属 Tenant。 */
public record RevocationFenceTarget(
        RevocationFenceTargetType type,
        UUID membershipId,
        UUID tenantId,
        UUID identityId) {

    public RevocationFenceTarget {
        if (type == null) {
            throw new IllegalArgumentException("Revocation Fence 目标类型不能为空");
        }
        if (type == RevocationFenceTargetType.IDENTITY) {
            if (identityId == null || identityId.version() != 7 || tenantId != null || membershipId != null) {
                throw new IllegalArgumentException("Identity Fence 必须仅绑定 UUIDv7 Identity");
            }
        } else if (tenantId == null || tenantId.version() != 7 || identityId != null) {
            throw new IllegalArgumentException("Revocation Fence Tenant 目标必须是 UUIDv7");
        } else if (type == RevocationFenceTargetType.TENANT && membershipId != null) {
            throw new IllegalArgumentException("Tenant Fence 不得绑定 Membership");
        } else if (type == RevocationFenceTargetType.MEMBERSHIP
                && (membershipId == null || membershipId.version() != 7)) {
            throw new IllegalArgumentException("Membership Fence 必须绑定 UUIDv7 Membership 与 Tenant");
        }
    }

    public static RevocationFenceTarget membership(UUID membershipId, UUID tenantId) {
        return new RevocationFenceTarget(RevocationFenceTargetType.MEMBERSHIP, membershipId, tenantId, null);
    }

    public static RevocationFenceTarget tenant(UUID tenantId) {
        return new RevocationFenceTarget(RevocationFenceTargetType.TENANT, null, tenantId, null);
    }

    public static RevocationFenceTarget identity(UUID identityId) {
        return new RevocationFenceTarget(RevocationFenceTargetType.IDENTITY, null, null, identityId);
    }

    public UUID targetId() {
        return switch (type) {
            case TENANT -> tenantId;
            case MEMBERSHIP -> membershipId;
            case IDENTITY -> identityId;
        };
    }
}
