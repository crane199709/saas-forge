package io.saas.forge.tenantaccess.api;

import io.saas.forge.tenantaccess.application.invitation.InvitationPasswordSetupService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invitation-password-setups")
public final class InvitationPasswordSetupController {
    private final InvitationPasswordSetupService service;

    public InvitationPasswordSetupController(InvitationPasswordSetupService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Result> request(@RequestHeader("Idempotency-Key") UUID requestId,
            @RequestBody Request body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Result(service.request(body == null ? null : body.token(), requestId).name()));
    }

    public record Request(String token) {}
    public record Result(String status) {}
}
