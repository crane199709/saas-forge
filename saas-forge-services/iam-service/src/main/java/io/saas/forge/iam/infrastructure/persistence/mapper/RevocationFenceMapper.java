package io.saas.forge.iam.infrastructure.persistence.mapper;

import io.saas.forge.iam.infrastructure.persistence.record.RevocationFenceRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface RevocationFenceMapper {
    int lockTarget(@Param("lockKey") String lockKey);

    RevocationFenceRow findByRequestId(@Param("revocationRequestId") UUID revocationRequestId);

    RevocationFenceRow findActiveTenant(@Param("tenantId") UUID tenantId);

    RevocationFenceRow findActiveMembership(@Param("membershipId") UUID membershipId);

    RevocationFenceRow findActiveIdentity(@Param("identityId") UUID identityId);

    RevocationFenceRow insert(@Param("row") RevocationFenceRow row);

    int release(
            @Param("revocationRequestId") UUID revocationRequestId,
            @Param("releasedAt") java.time.OffsetDateTime releasedAt);

    List<RevocationFenceRow> findActive();
}
