package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionController {
    private final TenantContextAccessor contexts;
    private final PermissionAuthority authority;

    public PermissionController(TenantContextAccessor contexts, PermissionAuthority authority) {
        this.contexts = contexts;
        this.authority = authority;
    }

    @GetMapping("/decision")
    public ResponseEntity<Decision> decide(@RequestParam String code) {
        if (!code.matches("^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$") || code.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Permission code");
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new Decision(code, authority.allowed(contexts.requireCurrent(), code)));
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Permission authority unavailable", unavailable);
        }
    }

    @GetMapping
    public ResponseEntity<Catalog> catalog() {
        try {
            if (!authority.isAdministrator(contexts.requireCurrent())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant Administrator required");
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Catalog(authority.catalog()));
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Permission authority unavailable", unavailable);
        }
    }

    public record Decision(String code, boolean allowed) {}

    public record Catalog(List<PermissionAuthority.PermissionEntry> items) {}
}
