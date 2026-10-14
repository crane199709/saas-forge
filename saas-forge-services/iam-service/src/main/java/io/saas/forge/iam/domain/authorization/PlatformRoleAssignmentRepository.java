package io.saas.forge.iam.domain.authorization;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PlatformRoleAssignmentRepository {

    PlatformRoleAssignment grant(PlatformRoleAssignment assignment);

    boolean hasActiveAssignment(UUID identityId, String roleKey, Instant at);

    Optional<PlatformRoleAssignment> findActive(UUID identityId, String roleKey, Instant at);
}
