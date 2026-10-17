package io.saas.forge.tenantaccess.application.organization;

import java.util.List;
import java.util.UUID;

public interface MembershipOrganizationRepository {
    List<UUID> list(UUID tenantId, UUID membershipId);

    void add(UUID tenantId, UUID membershipId, UUID unitId);

    void remove(UUID tenantId, UUID membershipId, UUID unitId);
}
