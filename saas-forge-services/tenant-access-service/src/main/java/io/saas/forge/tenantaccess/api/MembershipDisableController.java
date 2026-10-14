package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.membership.MembershipDisableService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members/{membershipId}/disables")
public final class MembershipDisableController {
    private final TenantContextAccessor contexts;
    private final MembershipDisableService service;

    public MembershipDisableController(TenantContextAccessor contexts, MembershipDisableService service) {
        this.contexts = contexts; this.service = service;
    }

    @PostMapping
    public ResponseEntity<MembershipDisableService.Progress> disable(@PathVariable UUID membershipId,
            @RequestHeader("Idempotency-Key") UUID operationId, HttpServletRequest request) {
        return response(service.disable(contexts.requireCurrent(), membershipId, operationId,
                TenantCreationController.traceId(request)));
    }

    @GetMapping
    public ResponseEntity<MembershipDisableService.Progress> read(@PathVariable UUID membershipId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.read(contexts.requireCurrent(), membershipId));
    }

    @PostMapping("/recoveries")
    public ResponseEntity<MembershipDisableService.Progress> recover(@PathVariable UUID membershipId,
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
