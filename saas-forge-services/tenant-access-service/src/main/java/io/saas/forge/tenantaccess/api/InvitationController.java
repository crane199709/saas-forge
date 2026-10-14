package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.invitation.InvitationRepository;
import io.saas.forge.tenantaccess.application.invitation.InvitationService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {
    private static final String MANAGE_PERMISSION = "tenant-access:invitation:manage";
    private final TenantContextAccessor contexts;
    private final PermissionAuthority permissions;
    private final InvitationService invitations;

    public InvitationController(TenantContextAccessor contexts, PermissionAuthority permissions,
            InvitationService invitations) {
        this.contexts = contexts;
        this.permissions = permissions;
        this.invitations = invitations;
    }

    @GetMapping
    public ResponseEntity<InvitationPage> list() {
        TenantContextSnapshot context = authorized();
        List<InvitationView> items = invitations.list(context).stream().map(InvitationView::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new InvitationPage(items));
    }

    @PostMapping
    public ResponseEntity<InvitationView> create(@RequestBody CreateInvitationRequest body,
            HttpServletRequest request) {
        TenantContextSnapshot context = authorized();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(InvitationView.from(invitations.create(context, body.email(),
                        TenantCreationController.traceId(request))));
    }

    @PostMapping("/{invitationId}/resends")
    public ResponseEntity<InvitationView> resend(@PathVariable UUID invitationId, HttpServletRequest request) {
        TenantContextSnapshot context = authorized();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(InvitationView.from(invitations.resend(context, invitationId,
                        TenantCreationController.traceId(request))));
    }

    @PostMapping("/{invitationId}/revocations")
    public ResponseEntity<Void> revoke(@PathVariable UUID invitationId, HttpServletRequest request) {
        TenantContextSnapshot context = authorized();
        invitations.revoke(context, invitationId, TenantCreationController.traceId(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private TenantContextSnapshot authorized() {
        TenantContextSnapshot context = contexts.requireCurrent();
        if (!permissions.isAdministrator(context) && !permissions.allowed(context, MANAGE_PERMISSION)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invitation 管理权限不足");
        }
        return context;
    }

    public record CreateInvitationRequest(String email) {}

    public record InvitationPage(List<InvitationView> items) {}

    public record InvitationView(UUID id, String email, String status, String deliveryStatus,
                                 Instant expiresAt, Instant createdAt, Instant updatedAt) {
        static InvitationView from(InvitationRepository.InvitationSummary value) {
            return new InvitationView(value.id(), value.email(), value.status(), value.deliveryStatus(),
                    value.expiresAt(), value.createdAt(), value.updatedAt());
        }
    }
}
