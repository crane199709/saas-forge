package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.session.RevocationFence;
import io.saas.forge.iam.domain.session.RevocationFenceRepository;
import io.saas.forge.iam.domain.session.RevocationFenceStatus;
import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class RevocationFenceService implements RevocationFenceOperations {
    private final RevocationFenceRepository fences;
    private final RevocationIndex index;
    private final Clock clock;

    public RevocationFenceService(RevocationFenceRepository fences, RevocationIndex index, Clock clock) {
        this.fences = fences;
        this.index = index;
        this.clock = clock;
    }

    /** 同一请求仅能重放同一目标；Redis 写入失败时 PostgreSQL 事务不得提交。 */
    @Transactional
    public RevocationFence establish(UUID revocationRequestId, RevocationFenceTarget target) {
        if (revocationRequestId == null || revocationRequestId.version() != 7 || target == null) {
            throw new IllegalArgumentException("revocationRequestId 与 Fence target 必须有效");
        }
        fences.lock(target);
        var existingRequest = fences.findByRequestId(revocationRequestId);
        if (existingRequest.isPresent()) {
            if (!existingRequest.orElseThrow().target().equals(target)) {
                throw new RevocationFenceConflictException();
            }
            RevocationFence existing = existingRequest.orElseThrow();
            if (existing.status() == RevocationFenceStatus.ACTIVE) {
                index.establishFence(existing);
            }
            return existing;
        }
        boolean occupied = target.type() == io.saas.forge.iam.domain.session.RevocationFenceTargetType.IDENTITY
                ? fences.findActiveIdentity(target.identityId()).isPresent()
                : fences.findActiveTenant(target.tenantId()).isPresent()
                || (target.membershipId() != null
                        && fences.findActiveMembership(target.membershipId()).isPresent());
        if (occupied) {
            throw new RevocationFenceConflictException();
        }
        RevocationFence created = fences.create(
                RevocationFence.establish(revocationRequestId, target, clock.instant()));
        index.establishFence(created);
        return created;
    }

    /** 锁与 Redis 检查必须在调用方的 Token 提交事务中再次执行以封闭并发窗口。 */
    @Transactional
    public void assertIssuable(UUID membershipId, UUID tenantId) {
        if (membershipId == null && tenantId == null) {
            if (!index.isReady()) {
                throw new RevocationIndexUnavailableException();
            }
            return;
        }
        RevocationFenceTarget target = RevocationFenceTarget.membership(membershipId, tenantId);
        fences.lock(target);
        if (index.isUserTokenFenced(target)) {
            throw new AccessContextUnavailableException();
        }
    }

    @Override
    @Transactional
    public void assertIdentityIssuable(UUID identityId) {
        RevocationFenceTarget target = RevocationFenceTarget.identity(identityId);
        fences.lock(target);
        if (index.isIdentityFenced(identityId)) {
            throw new AccessContextUnavailableException();
        }
    }
}
