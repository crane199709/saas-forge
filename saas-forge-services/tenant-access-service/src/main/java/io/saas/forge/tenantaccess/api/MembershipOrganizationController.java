package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.organization.MembershipOrganizationService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members/{membershipId}/organization-units")
public final class MembershipOrganizationController {
    private final TenantContextAccessor contexts;
    private final MembershipOrganizationService service;

    public MembershipOrganizationController(TenantContextAccessor contexts, MembershipOrganizationService service) {
        this.contexts = contexts;
        this.service = service;
    }

    public record UnitList(List<UUID> unitIds) {}
    public record AddUnit(UUID unitId) {}

    @GetMapping
    public ResponseEntity<UnitList> list(@PathVariable UUID membershipId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new UnitList(service.list(contexts.requireCurrent(), membershipId)));
    }

    @PostMapping
    public ResponseEntity<Void> add(@PathVariable UUID membershipId, @RequestBody AddUnit body) {
        service.add(contexts.requireCurrent(), membershipId, body == null ? null : body.unitId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/{unitId}")
    public ResponseEntity<Void> remove(@PathVariable UUID membershipId, @PathVariable UUID unitId) {
        service.remove(contexts.requireCurrent(), membershipId, unitId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
