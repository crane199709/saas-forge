package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.contract.api.PermissionsApi;
import io.saas.forge.tenantaccess.contract.model.EffectivePermissions;
import io.saas.forge.tenantaccess.contract.model.PermissionCatalog;
import io.saas.forge.tenantaccess.contract.model.PermissionDecision;
import io.saas.forge.tenantaccess.contract.model.PermissionEntry;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PermissionController implements PermissionsApi {
    private final TenantContextAccessor contexts;
    private final PermissionAuthority authority;

    public PermissionController(TenantContextAccessor contexts, PermissionAuthority authority) {
        this.contexts = contexts;
        this.authority = authority;
    }

    @Override
    public ResponseEntity<PermissionDecision> decidePermission(String code) {
        if (!code.matches("^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$") || code.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Permission code");
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new PermissionDecision(code, authority.allowed(contexts.requireCurrent(), code)));
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Permission authority unavailable", unavailable);
        }
    }

    @Override
    public ResponseEntity<PermissionCatalog> listPermissions() {
        try {
            if (!authority.isAdministrator(contexts.requireCurrent())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant Administrator required");
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new PermissionCatalog(authority.catalog().stream()
                            .map(entry -> new PermissionEntry(entry.code(), entry.enabled())).toList()));
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Permission authority unavailable", unavailable);
        }
    }

    @Override
    public ResponseEntity<EffectivePermissions> listEffectivePermissions() {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new EffectivePermissions(authority.effective(contexts.requireCurrent())));
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Permission authority unavailable", unavailable);
        }
    }

}
