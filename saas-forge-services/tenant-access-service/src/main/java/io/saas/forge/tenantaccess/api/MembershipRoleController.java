package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.authorization.MembershipRoleService;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcMembershipRoleRepository.Assignment;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class MembershipRoleController {
    private final TenantContextAccessor contexts;
    private final MembershipRoleService service;

    public MembershipRoleController(TenantContextAccessor contexts, MembershipRoleService service) {
        this.contexts = contexts;
        this.service = service;
    }

    public record RoleAssignments(List<Assignment> items) {}

    public ResponseEntity<RoleAssignments> list(UUID membershipId) {
        return noStore(service.list(contexts.requireCurrent(), membershipId));
    }

    public ResponseEntity<RoleAssignments> assign(UUID membershipId, UUID roleId) {
        return noStore(service.assign(contexts.requireCurrent(), membershipId, roleId));
    }

    public ResponseEntity<RoleAssignments> revoke(UUID membershipId, UUID roleId) {
        return noStore(service.revoke(contexts.requireCurrent(), membershipId, roleId));
    }

    private static ResponseEntity<RoleAssignments> noStore(List<Assignment> assignments) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RoleAssignments(assignments));
    }
}
