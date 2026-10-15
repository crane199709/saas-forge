package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.authorization.MembershipRoleService;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcMembershipRoleRepository.Assignment;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members/{membershipId}/roles")
public class MembershipRoleController {
    private final TenantContextAccessor contexts;
    private final MembershipRoleService service;

    public MembershipRoleController(TenantContextAccessor contexts, MembershipRoleService service) {
        this.contexts = contexts;
        this.service = service;
    }

    public record RoleAssignments(List<Assignment> items) {}

    @GetMapping
    public ResponseEntity<RoleAssignments> list(@PathVariable UUID membershipId) {
        return noStore(service.list(contexts.requireCurrent(), membershipId));
    }

    @PutMapping("/{roleId}")
    public ResponseEntity<RoleAssignments> assign(@PathVariable UUID membershipId, @PathVariable UUID roleId) {
        return noStore(service.assign(contexts.requireCurrent(), membershipId, roleId));
    }

    @DeleteMapping("/{roleId}")
    public ResponseEntity<RoleAssignments> revoke(@PathVariable UUID membershipId, @PathVariable UUID roleId) {
        return noStore(service.revoke(contexts.requireCurrent(), membershipId, roleId));
    }

    private static ResponseEntity<RoleAssignments> noStore(List<Assignment> assignments) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RoleAssignments(assignments));
    }
}
