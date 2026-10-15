package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.invitation.InvitationRepository;
import io.saas.forge.tenantaccess.application.invitation.InvitationService;
import io.saas.forge.tenantaccess.contract.api.InvitationsApi;
import io.saas.forge.tenantaccess.contract.model.CreateInvitationRequest;
import io.saas.forge.tenantaccess.contract.model.Invitation;
import io.saas.forge.tenantaccess.contract.model.InvitationPage;
import io.saas.forge.tenantaccess.contract.model.InvitationPasswordSetupRequest;
import io.saas.forge.tenantaccess.contract.model.InvitationPasswordSetupResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InvitationController implements InvitationsApi {
    private static final String MANAGE_PERMISSION = "tenant-access:invitation:manage";
    private final TenantContextAccessor contexts;
    private final PermissionAuthority permissions;
    private final InvitationService invitations;
    private final InvitationPasswordSetupController passwordSetup;

    public InvitationController(TenantContextAccessor contexts, PermissionAuthority permissions,
            InvitationService invitations, InvitationPasswordSetupController passwordSetup) {
        this.contexts = contexts;
        this.permissions = permissions;
        this.invitations = invitations;
        this.passwordSetup = passwordSetup;
    }

    @Override
    public ResponseEntity<InvitationPage> listInvitations() {
        TenantContextSnapshot context = authorized();
        List<Invitation> items = invitations.list(context).stream().map(InvitationController::response).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new InvitationPage(items));
    }

    @Override
    public ResponseEntity<Invitation> createInvitation(CreateInvitationRequest body) {
        TenantContextSnapshot context = authorized();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(response(invitations.create(context, body.getEmail(),
                        TenantCreationController.traceId(currentRequest()))));
    }

    @Override
    public ResponseEntity<Invitation> resendInvitation(UUID invitationId) {
        TenantContextSnapshot context = authorized();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(response(invitations.resend(context, invitationId,
                        TenantCreationController.traceId(currentRequest()))));
    }

    @Override
    public ResponseEntity<Void> revokeInvitation(UUID invitationId) {
        TenantContextSnapshot context = authorized();
        invitations.revoke(context, invitationId, TenantCreationController.traceId(currentRequest()));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    public ResponseEntity<InvitationPasswordSetupResult> requestInvitationPasswordSetup(
            UUID idempotencyKey, String csrf, InvitationPasswordSetupRequest body) {
        return passwordSetup.request(idempotencyKey, body == null ? null : body.getToken());
    }

    private TenantContextSnapshot authorized() {
        TenantContextSnapshot context = contexts.requireCurrent();
        if (!permissions.isAdministrator(context) && !permissions.allowed(context, MANAGE_PERMISSION)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invitation 管理权限不足");
        }
        return context;
    }

    private static HttpServletRequest currentRequest() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }

    private static Invitation response(InvitationRepository.InvitationSummary value) {
        return new Invitation(value.id(), value.email(), Invitation.StatusEnum.valueOf(value.status()),
                Invitation.DeliveryStatusEnum.valueOf(value.deliveryStatus()),
                utc(value.expiresAt()), utc(value.createdAt()), utc(value.updatedAt()));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
