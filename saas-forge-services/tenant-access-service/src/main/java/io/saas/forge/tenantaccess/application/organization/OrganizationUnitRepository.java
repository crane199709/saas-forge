package io.saas.forge.tenantaccess.application.organization;

import java.util.List;
import java.util.UUID;

public interface OrganizationUnitRepository {
    List<OrganizationUnit> list(UUID tenantId);

    OrganizationUnit create(UUID tenantId, String name, UUID parentId);

    OrganizationUnit rename(UUID tenantId, UUID unitId, String name);

    OrganizationUnit move(UUID tenantId, UUID unitId, UUID parentId);

    void delete(UUID tenantId, UUID unitId);
}
