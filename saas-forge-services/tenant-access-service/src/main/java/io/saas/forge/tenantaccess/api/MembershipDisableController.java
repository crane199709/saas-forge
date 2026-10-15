package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.membership.MembershipDisableService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public final class MembershipDisableController {
    private final TenantContextAccessor contexts;
    private final MembershipDisableService service;

    public MembershipDisableController(TenantContextAccessor contexts, MembershipDisableService service) {
        this.contexts = contexts; this.service = service;
    }

    public ResponseEntity<MembershipDisableService.Progress> disable(UUID membershipId,
            UUID operationId, HttpServletRequest request) {
        return response(service.disable(contexts.requireCurrent(), membershipId, operationId,
                TenantCreationController.traceId(request)));
    }

    public ResponseEntity<MembershipDisableService.Progress> read(UUID membershipId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.read(contexts.requireCurrent(), membershipId));
    }

    public ResponseEntity<MembershipDisableService.Progress> recover(UUID membershipId,
            HttpServletRequest request) {
        return response(service.recover(contexts.requireCurrent(), membershipId,
                TenantCreationController.traceId(request)));
    }

    private static ResponseEntity<MembershipDisableService.Progress> response(
            MembershipDisableService.Progress result) {
        return ResponseEntity.status(result.pending() ? 202 : 200)
                .cacheControl(CacheControl.noStore()).body(result);
    }
}
