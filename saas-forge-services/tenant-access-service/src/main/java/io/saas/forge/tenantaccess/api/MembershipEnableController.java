package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.membership.MembershipEnableService;
import io.saas.forge.tenantaccess.application.membership.MembershipEnableFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public final class MembershipEnableController {
    private final TenantContextAccessor contexts;
    private final MembershipEnableService service;

    public MembershipEnableController(TenantContextAccessor contexts, MembershipEnableService service) {
        this.contexts = contexts; this.service = service;
    }

    public ResponseEntity<MembershipEnableService.Progress> enable(UUID membershipId,
            UUID operationId, HttpServletRequest request) {
        return response(service.enable(contexts.requireCurrent(), membershipId, operationId,
                TenantCreationController.traceId(request)));
    }

    public ResponseEntity<MembershipEnableService.Progress> read(UUID membershipId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.read(contexts.requireCurrent(), membershipId));
    }

    private static ResponseEntity<MembershipEnableService.Progress> response(MembershipEnableService.Progress result) {
        if ("QUOTA_REJECTED".equals(result.operationStatus()))
            throw new MembershipEnableFailure("MAX_USERS_EXCEEDED");
        if ("COMPENSATED".equals(result.operationStatus()))
            throw new MembershipEnableFailure("MEMBERSHIP_ENABLE_COMPENSATED");
        return ResponseEntity.status(result.pending() ? 202 : 200)
                .cacheControl(CacheControl.noStore()).body(result);
    }
}
