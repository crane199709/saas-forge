package io.saas.forge.tenantaccess.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.authorization.TenantRoleService;
import io.saas.forge.tenantaccess.application.organization.OrganizationUnitService;
import io.saas.forge.tenantaccess.contract.model.CreateOrganizationUnitRequest;
import io.saas.forge.tenantaccess.contract.model.MoveOrganizationUnitRequest;
import io.saas.forge.tenantaccess.contract.model.RenameOrganizationUnitRequest;
import io.saas.forge.tenantaccess.contract.model.TenantRoleChange;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcTenantRoleRepository.Role;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class Stage4DirectoryControllerTest {
    private static final TenantContextSnapshot CONTEXT = new TenantContextSnapshot(
            UUID.fromString("0199c200-0000-7000-8000-000000000001"),
            UUID.fromString("0199c200-0000-7000-8000-000000000002"),
            UUID.fromString("0199c200-0000-7000-8000-000000000003"));
    private static final UUID UNIT = UUID.fromString("0199c200-0000-7000-8000-000000000004");
    private static final UUID PARENT = UUID.fromString("0199c200-0000-7000-8000-000000000005");

    private final TenantContextAccessor contexts = mock(TenantContextAccessor.class);
    private final PermissionAuthority authority = mock(PermissionAuthority.class);

    @BeforeEach
    void currentTenant() {
        when(contexts.requireCurrent()).thenReturn(CONTEXT);
    }

    @Test
    void organizationRoutesPreserveTenantScopeAndTreeRepresentation() {
        var service = mock(OrganizationUnitService.class);
        var controller = new OrganizationUnitController(contexts, service);
        var unit = new io.saas.forge.tenantaccess.application.organization.OrganizationUnit(UNIT, PARENT,
                "Engineering", 2);
        when(service.list(CONTEXT)).thenReturn(List.of(unit));
        when(service.create(CONTEXT, "Engineering", PARENT)).thenReturn(unit);
        when(service.rename(CONTEXT, UNIT, "Platform")).thenReturn(
                new io.saas.forge.tenantaccess.application.organization.OrganizationUnit(UNIT, PARENT,
                        "Platform", 2));
        when(service.move(CONTEXT, UNIT, null)).thenReturn(
                new io.saas.forge.tenantaccess.application.organization.OrganizationUnit(UNIT, null,
                        "Platform", 2));

        var listed = controller.listOrganizationUnits();
        assertThat(listed.getBody().getOrganizationId()).isEqualTo(CONTEXT.tenantId());
        assertThat(listed.getBody().getItems()).singleElement().satisfies(item -> {
            assertThat(item.getId()).isEqualTo(UNIT);
            assertThat(item.getParentId()).isEqualTo(PARENT);
            assertThat(item.getMemberCount()).isEqualTo(2);
        });
        assertNoStore(listed);

        var created = controller.createOrganizationUnit(new CreateOrganizationUnitRequest("Engineering").parentId(PARENT));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().getId()).isEqualTo(UNIT);
        assertNoStore(created);
        assertThat(controller.renameOrganizationUnit(UNIT, new RenameOrganizationUnitRequest("Platform"))
                .getBody().getName()).isEqualTo("Platform");
        assertThat(controller.moveOrganizationUnit(UNIT, new MoveOrganizationUnitRequest().parentId(null))
                .getBody().getParentId()).isNull();
        assertThat(controller.deleteOrganizationUnit(UNIT).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete(CONTEXT, UNIT);
    }

    @Test
    void roleRoutesReturnPublishedPermissionAssignmentsWithoutCaching() {
        var service = mock(TenantRoleService.class);
        var controller = new TenantRoleController(contexts, service);
        var role = new Role(UNIT, "PROJECT_VIEWER", false, List.of("example:project:list"), 2);
        when(service.list(CONTEXT)).thenReturn(List.of(role));
        when(service.find(CONTEXT, UNIT)).thenReturn(role);
        when(service.create(CONTEXT, role.roleKey(), role.permissions())).thenReturn(role);
        when(service.update(CONTEXT, UNIT, role.roleKey(), role.permissions())).thenReturn(role);

        var page = controller.listTenantRoles();
        assertThat(page.getBody().getItems()).singleElement().satisfies(item -> {
            assertThat(item.getId()).isEqualTo(UNIT);
            assertThat(item.getAssignedMembers()).isEqualTo(2);
            assertThat(item.getPermissions()).containsExactly("example:project:list");
        });
        assertNoStore(page);
        assertThat(controller.getTenantRole(UNIT).getBody().getRoleKey()).isEqualTo(role.roleKey());
        var change = new TenantRoleChange(role.roleKey(), role.permissions());
        assertThat(controller.createTenantRole(change).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.updateTenantRole(UNIT, change).getBody().getId()).isEqualTo(UNIT);
        assertThat(controller.deleteTenantRole(UNIT).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete(CONTEXT, UNIT);
    }

    @Test
    void permissionDecisionValidatesCodeAndUsesCurrentAuthority() {
        var controller = new PermissionController(contexts, authority);
        when(authority.allowed(CONTEXT, "example:project:list")).thenReturn(true);
        var decision = controller.decidePermission("example:project:list");
        assertThat(decision.getBody().getAllowed()).isTrue();
        assertNoStore(decision);
        assertThatThrownBy(() -> controller.decidePermission("example:*:list"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(authority).allowed(CONTEXT, "example:project:list");
    }

    @Test
    void catalogIsAdministratorOnlyWhileEffectivePermissionsRemainAvailableToMembers() {
        var controller = new PermissionController(contexts, authority);
        when(authority.effective(CONTEXT)).thenReturn(List.of("example:project:list"));
        assertThat(controller.listEffectivePermissions().getBody().getCodes())
                .containsExactly("example:project:list");
        assertThatThrownBy(controller::listPermissions).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        when(authority.isAdministrator(CONTEXT)).thenReturn(true);
        when(authority.catalog()).thenReturn(List.of(
                new PermissionAuthority.PermissionEntry("example:project:list", true),
                new PermissionAuthority.PermissionEntry("example:task:delete", false)));
        var catalog = controller.listPermissions();
        assertThat(catalog.getBody().getItems()).extracting(item -> item.getCode())
                .containsExactly("example:project:list", "example:task:delete");
        assertThat(catalog.getBody().getItems().get(1).getEnabled()).isFalse();
        assertNoStore(catalog);
    }

    @Test
    void unavailablePermissionAuthorityNeverProducesAnAllowDecision() {
        var controller = new PermissionController(contexts, authority);
        when(authority.allowed(CONTEXT, "example:project:list"))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(() -> controller.decidePermission("example:project:list"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        when(authority.effective(CONTEXT))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(controller::listEffectivePermissions)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static void assertNoStore(org.springframework.http.ResponseEntity<?> response) {
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
