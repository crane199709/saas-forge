package io.saas.forge.iam.domain.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RevocationFenceRepository {
    /** Tenant 锁先于 Membership 锁；建 Fence 与 Token 提交共用该顺序。 */
    void lock(RevocationFenceTarget target);

    Optional<RevocationFence> findByRequestId(UUID revocationRequestId);

    Optional<RevocationFence> findActiveTenant(UUID tenantId);

    Optional<RevocationFence> findActiveMembership(UUID membershipId);

    Optional<RevocationFence> findActiveIdentity(UUID identityId);

    RevocationFence create(RevocationFence fence);

    boolean release(UUID revocationRequestId, java.time.Instant releasedAt);

    List<RevocationFence> findActive();
}
