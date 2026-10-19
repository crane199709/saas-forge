package io.saas.forge.iam.infrastructure.persistence;

import io.saas.forge.iam.domain.authorization.PlatformRoleAssignment;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.infrastructure.persistence.mapper.PlatformRoleAssignmentMapper;
import io.saas.forge.iam.infrastructure.persistence.record.PlatformRoleAssignmentRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPlatformRoleAssignmentRepository implements PlatformRoleAssignmentRepository {
    private final PlatformRoleAssignmentMapper mapper;

    public MyBatisPlatformRoleAssignmentRepository(PlatformRoleAssignmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PlatformRoleAssignment grant(PlatformRoleAssignment assignment) {
        PlatformRoleAssignmentRow row = new PlatformRoleAssignmentRow();
        row.setIdentityId(assignment.identityId());
        row.setRoleKey(assignment.roleKey());
        row.setAssignedAt(IamTime.asOffsetDateTime(assignment.assignedAt()));
        row.setRevokedAt(IamTime.asOffsetDateTime(assignment.revokedAt()));
        return toDomain(mapper.insert(row));
    }

    @Override
    public boolean hasActiveAssignment(UUID identityId, String roleKey, Instant at) {
        return mapper.countActive(identityId, roleKey, IamTime.asOffsetDateTime(at)) > 0;
    }

    @Override
    public Optional<PlatformRoleAssignment> findActive(UUID identityId, String roleKey, Instant at) {
        return Optional.ofNullable(mapper.findActive(identityId, roleKey, IamTime.asOffsetDateTime(at)))
                .map(MyBatisPlatformRoleAssignmentRepository::toDomain);
    }

    private static PlatformRoleAssignment toDomain(PlatformRoleAssignmentRow row) {
        return new PlatformRoleAssignment(row.getId(), row.getIdentityId(), row.getRoleKey(),
                IamTime.asInstant(row.getAssignedAt()), IamTime.asInstant(row.getRevokedAt()));
    }
}
