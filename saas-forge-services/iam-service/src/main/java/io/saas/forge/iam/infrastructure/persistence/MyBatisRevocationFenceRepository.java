package io.saas.forge.iam.infrastructure.persistence;

import io.saas.forge.iam.domain.session.RevocationFence;
import io.saas.forge.iam.domain.session.RevocationFenceRepository;
import io.saas.forge.iam.domain.session.RevocationFenceStatus;
import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import io.saas.forge.iam.domain.session.RevocationFenceTargetType;
import io.saas.forge.iam.infrastructure.persistence.mapper.RevocationFenceMapper;
import io.saas.forge.iam.infrastructure.persistence.record.RevocationFenceRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisRevocationFenceRepository implements RevocationFenceRepository {
    private final RevocationFenceMapper mapper;

    public MyBatisRevocationFenceRepository(RevocationFenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void lock(RevocationFenceTarget target) {
        if (target.type() == RevocationFenceTargetType.IDENTITY) {
            mapper.lockTarget("revocation-fence:identity:" + target.identityId());
            return;
        }
        mapper.lockTarget("revocation-fence:tenant:" + target.tenantId());
        if (target.type() == RevocationFenceTargetType.MEMBERSHIP) {
            mapper.lockTarget("revocation-fence:membership:" + target.membershipId());
        }
    }

    @Override
    public Optional<RevocationFence> findByRequestId(UUID revocationRequestId) {
        return Optional.ofNullable(mapper.findByRequestId(revocationRequestId))
                .map(MyBatisRevocationFenceRepository::toDomain);
    }

    @Override
    public Optional<RevocationFence> findActiveTenant(UUID tenantId) {
        return Optional.ofNullable(mapper.findActiveTenant(tenantId))
                .map(MyBatisRevocationFenceRepository::toDomain);
    }

    @Override
    public Optional<RevocationFence> findActiveMembership(UUID membershipId) {
        return Optional.ofNullable(mapper.findActiveMembership(membershipId))
                .map(MyBatisRevocationFenceRepository::toDomain);
    }

    @Override
    public Optional<RevocationFence> findActiveIdentity(UUID identityId) {
        return Optional.ofNullable(mapper.findActiveIdentity(identityId))
                .map(MyBatisRevocationFenceRepository::toDomain);
    }

    @Override
    public RevocationFence create(RevocationFence fence) {
        RevocationFenceRow row = new RevocationFenceRow();
        row.setRevocationRequestId(fence.revocationRequestId());
        row.setTargetType(fence.target().type().name());
        row.setTargetId(fence.target().targetId());
        row.setTenantId(fence.target().tenantId());
        row.setMembershipId(fence.target().membershipId());
        row.setIdentityId(fence.target().identityId());
        row.setFenceStatus(fence.status().name());
        row.setEstablishedAt(IamTime.asOffsetDateTime(fence.establishedAt()));
        row.setReleasedAt(IamTime.asOffsetDateTime(fence.releasedAt()));
        return toDomain(mapper.insert(row));
    }

    @Override
    public boolean release(UUID revocationRequestId, java.time.Instant releasedAt) {
        return mapper.release(revocationRequestId, IamTime.asOffsetDateTime(releasedAt)) == 1;
    }

    @Override
    public List<RevocationFence> findActive() {
        return mapper.findActive().stream().map(MyBatisRevocationFenceRepository::toDomain).toList();
    }

    private static RevocationFence toDomain(RevocationFenceRow row) {
        RevocationFenceTargetType type = RevocationFenceTargetType.valueOf(row.getTargetType());
        return new RevocationFence(
                row.getRevocationRequestId(),
                new RevocationFenceTarget(type, row.getMembershipId(), row.getTenantId(), row.getIdentityId()),
                RevocationFenceStatus.valueOf(row.getFenceStatus()),
                IamTime.asInstant(row.getEstablishedAt()),
                IamTime.asInstant(row.getReleasedAt()));
    }
}
