package io.saas.forge.tenantaccess.application.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationUnitServiceTest {
    private boolean allowed;
    private final PermissionAuthority authority = new PermissionAuthority() {
        @Override public boolean allowed(TenantContextSnapshot current, String code) { return allowed; }
        @Override public boolean isAdministrator(TenantContextSnapshot current) { return allowed; }
        @Override public List<PermissionEntry> catalog() { return List.of(); }
        @Override public List<String> effective(TenantContextSnapshot current) { return List.of(); }
    };
    private final TenantContextSnapshot context = new TenantContextSnapshot(
            UUID.fromString("0199c200-0000-7000-8000-000000000001"),
            UUID.fromString("0199c200-0000-7000-8000-000000000002"),
            UUID.fromString("0199c200-0000-7000-8000-000000000003"));

    @Test
    void organizationChangesRequireCurrentPermission() {
        var service = new OrganizationUnitService(authority, null, null, null);
        assertThatThrownBy(() -> service.create(context, "Engineering", null))
                .isInstanceOf(OrganizationUnitService.Forbidden.class);
    }

    @Test
    void authorizedOrganizationReadReturnsCurrentTree() {
        allowed = true;
        var id = UUID.fromString("0199c200-0000-7000-8000-000000000004");
        var repository = new OrganizationUnitRepository() {
            @Override public List<OrganizationUnit> list(UUID tenantId) {
                return List.of(new OrganizationUnit(id, null, "Engineering", 0));
            }
            @Override public OrganizationUnit create(UUID tenantId, String name, UUID parentId) { throw new AssertionError(); }
            @Override public OrganizationUnit rename(UUID tenantId, UUID unitId, String name) { throw new AssertionError(); }
            @Override public OrganizationUnit move(UUID tenantId, UUID unitId, UUID parentId) { throw new AssertionError(); }
            @Override public void delete(UUID tenantId, UUID unitId) { throw new AssertionError(); }
        };
        var service = new OrganizationUnitService(authority, repository, null, null);
        assertThat(service.list(context)).extracting(OrganizationUnit::id).containsExactly(id);
    }
}
