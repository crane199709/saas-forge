package io.saas.forge.tenantaccess.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.invitation.InvitationRepository.InvitationSummary;
import io.saas.forge.tenantaccess.application.invitation.InvitationService;
import io.saas.forge.tenantaccess.contract.model.CreateInvitationRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

class InvitationControllerBehaviorTest {
    private static final TenantContextSnapshot CONTEXT = new TenantContextSnapshot(
            UUID.fromString("0199c200-0000-7000-8000-000000000001"),
            UUID.fromString("0199c200-0000-7000-8000-000000000002"),
            UUID.fromString("0199c200-0000-7000-8000-000000000003"));
    private static final UUID INVITATION = UUID.fromString("0199c200-0000-7000-8000-000000000004");
    private static final Instant AT = Instant.parse("2026-10-15T00:00:00Z");
    private final TenantContextAccessor contexts = mock(TenantContextAccessor.class);
    private final PermissionAuthority authority = mock(PermissionAuthority.class);
    private final InvitationService service = mock(InvitationService.class);
    private final InvitationController controller = new InvitationController(contexts, authority, service,
            mock(InvitationPasswordSetupController.class));

    @BeforeEach
    void currentRequest() {
        when(contexts.requireCurrent()).thenReturn(CONTEXT);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void delegatedInvitationManagerCanListCreateResendAndRevokeWithNoStoreResponses() {
        when(authority.allowed(CONTEXT, "tenant-access:invitation:manage")).thenReturn(true);
        var pending = new InvitationSummary(INVITATION, "guest@example.com", "PENDING", "SENT",
                AT.plusSeconds(604800), AT, AT);
        when(service.list(CONTEXT)).thenReturn(List.of(pending));
        when(service.create(CONTEXT, "guest@example.com", null)).thenReturn(pending);
        when(service.resend(CONTEXT, INVITATION, null)).thenReturn(pending);

        var page = controller.listInvitations();
        assertThat(page.getBody().getItems()).singleElement().satisfies(invitation -> {
            assertThat(invitation.getId()).isEqualTo(INVITATION);
            assertThat(invitation.getEmail()).isEqualTo("guest@example.com");
            assertThat(invitation.getStatus().getValue()).isEqualTo("PENDING");
            assertThat(invitation.getExpiresAt().toInstant()).isEqualTo(AT.plusSeconds(604800));
        });
        assertNoStore(page);

        var created = controller.createInvitation(new CreateInvitationRequest("guest@example.com"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().getDeliveryStatus().getValue()).isEqualTo("SENT");
        assertNoStore(created);
        assertThat(controller.resendInvitation(INVITATION).getBody().getId()).isEqualTo(INVITATION);
        var revoked = controller.revokeInvitation(INVITATION);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertNoStore(revoked);
        verify(service).revoke(CONTEXT, INVITATION, null);
    }

    @Test
    void unprivilegedMemberCannotObserveOrMutateInvitations() {
        for (Runnable action : List.<Runnable>of(controller::listInvitations,
                () -> controller.createInvitation(new CreateInvitationRequest("guest@example.com")),
                () -> controller.resendInvitation(INVITATION),
                () -> controller.revokeInvitation(INVITATION))) {
            assertThatThrownBy(action::run).isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
        verifyNoInteractions(service);
    }

    private static void assertNoStore(org.springframework.http.ResponseEntity<?> response) {
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
