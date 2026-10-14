package io.saas.forge.iam.infrastructure.persistence.mapper;

import io.saas.forge.iam.infrastructure.persistence.record.PlatformRoleAssignmentRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface PlatformRoleAssignmentMapper {

    PlatformRoleAssignmentRow insert(@Param("row") PlatformRoleAssignmentRow row);

    int countActive(
            @Param("identityId") UUID identityId,
            @Param("roleKey") String roleKey,
            @Param("at") OffsetDateTime at);

    PlatformRoleAssignmentRow findActive(
            @Param("identityId") UUID identityId,
            @Param("roleKey") String roleKey,
            @Param("at") OffsetDateTime at);
}
