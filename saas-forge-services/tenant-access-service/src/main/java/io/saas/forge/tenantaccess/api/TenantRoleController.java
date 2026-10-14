package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.authorization.TenantRoleService;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcTenantRoleRepository.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant-roles")
public class TenantRoleController {
    private final TenantContextAccessor contexts;
    private final TenantRoleService service;

    public TenantRoleController(TenantContextAccessor contexts, TenantRoleService service) {
        this.contexts = contexts;
        this.service = service;
    }

    public record Roles(List<Role> items) {}
    public record Change(String roleKey, List<String> permissions) {}

    @GetMapping
    public ResponseEntity<Roles> list() {
        return noStore(new Roles(service.list(contexts.requireCurrent())));
    }

    @GetMapping("/{roleId}")
    public ResponseEntity<Role> find(@PathVariable UUID roleId) {
        return noStore(service.find(contexts.requireCurrent(), roleId));
    }

    @PostMapping
    public ResponseEntity<Role> create(@RequestBody Change change) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(contexts.requireCurrent(), change.roleKey(), change.permissions()));
    }

    @PutMapping("/{roleId}")
    public ResponseEntity<Role> update(@PathVariable UUID roleId, @RequestBody Change change) {
        return noStore(service.update(contexts.requireCurrent(), roleId, change.roleKey(), change.permissions()));
    }

    @DeleteMapping("/{roleId}")
    public ResponseEntity<Void> delete(@PathVariable UUID roleId) {
        service.delete(contexts.requireCurrent(), roleId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
