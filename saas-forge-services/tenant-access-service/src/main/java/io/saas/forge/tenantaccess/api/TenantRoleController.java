package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.authorization.TenantRoleService;
import io.saas.forge.tenantaccess.contract.api.TenantRolesApi;
import io.saas.forge.tenantaccess.contract.model.TenantRole;
import io.saas.forge.tenantaccess.contract.model.TenantRoleChange;
import io.saas.forge.tenantaccess.contract.model.TenantRolePage;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcTenantRoleRepository.Role;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TenantRoleController implements TenantRolesApi {
    private final TenantContextAccessor contexts;
    private final TenantRoleService service;

    public TenantRoleController(TenantContextAccessor contexts, TenantRoleService service) {
        this.contexts = contexts;
        this.service = service;
    }

    @Override
    public ResponseEntity<TenantRolePage> listTenantRoles() {
        return noStore(new TenantRolePage(service.list(contexts.requireCurrent()).stream()
                .map(TenantRoleController::response).toList()));
    }

    @Override
    public ResponseEntity<TenantRole> getTenantRole(UUID roleId) {
        return noStore(response(service.find(contexts.requireCurrent(), roleId)));
    }

    @Override
    public ResponseEntity<TenantRole> createTenantRole(TenantRoleChange change) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(response(service.create(contexts.requireCurrent(), change.getRoleKey(), change.getPermissions())));
    }

    @Override
    public ResponseEntity<TenantRole> updateTenantRole(UUID roleId, TenantRoleChange change) {
        return noStore(response(service.update(contexts.requireCurrent(), roleId,
                change.getRoleKey(), change.getPermissions())));
    }

    @Override
    public ResponseEntity<Void> deleteTenantRole(UUID roleId) {
        service.delete(contexts.requireCurrent(), roleId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static TenantRole response(Role role) {
        return new TenantRole(role.id(), role.roleKey(), role.systemManaged(), role.permissions(),
                role.assignedMembers());
    }
}
