package io.saas.forge.tenantaccess.api;

import io.saas.forge.tenantaccess.application.invitation.InvitationPasswordSetupService;
import io.saas.forge.tenantaccess.contract.model.InvitationPasswordSetupResult;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public final class InvitationPasswordSetupController {
    private final InvitationPasswordSetupService service;

    public InvitationPasswordSetupController(InvitationPasswordSetupService service) {
        this.service = service;
    }

    public ResponseEntity<InvitationPasswordSetupResult> request(UUID requestId, String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new InvitationPasswordSetupResult(InvitationPasswordSetupResult.StatusEnum
                        .valueOf(service.request(token, requestId).name())));
    }
}
