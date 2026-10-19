package io.saas.forge.tenantaccess.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.brand.BrandAsset;
import io.saas.forge.tenantaccess.application.brand.BrandAssetService;
import io.saas.forge.tenantaccess.application.brand.BrandPng;
import io.saas.forge.tenantaccess.application.invitation.InvitationPasswordSetupGateway;
import io.saas.forge.tenantaccess.application.invitation.InvitationPasswordSetupService;
import io.saas.forge.tenantaccess.application.invitation.InvitationService;
import io.saas.forge.tenantaccess.application.membership.MembershipDisableService;
import io.saas.forge.tenantaccess.application.membership.MembershipDisableFailure;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

class GeneratedTenantHttpControllerTest {
    private static final UUID MEMBERSHIP = UUID.fromString("019535d9-0001-7000-8000-000000000002");
    private static final UUID OPERATION = UUID.fromString("019535d9-0001-7000-8000-000000000004");
    private static final TenantContextSnapshot CONTEXT = new TenantContextSnapshot(
            UUID.fromString("019535d9-0001-7000-8000-000000000001"),
            MEMBERSHIP, UUID.fromString("019535d9-0001-7000-8000-000000000003"));

    @Test
    void generatedBrandUploadRouteAcceptsPngBody() throws Exception {
        var contexts = mock(TenantContextAccessor.class);
        var service = mock(BrandAssetService.class);
        var assetId = UUID.fromString("019535d9-0001-7000-8000-000000000009");
        when(contexts.requireCurrent()).thenReturn(CONTEXT);
        when(service.upload(eq(CONTEXT), eq(BrandPng.Kind.LOGO), any(byte[].class)))
                .thenReturn(new BrandAsset(assetId, CONTEXT.tenantId(), BrandPng.Kind.LOGO,
                        "/brands/" + assetId, 32, 32));
        var mvc = MockMvcBuilders.standaloneSetup(new BrandAssetController(contexts, service)).build();

        mvc.perform(post("/api/v1/brand-assets/logo")
                        .contentType(MediaType.IMAGE_PNG)
                        .content(new byte[] {(byte) 137, 80, 78, 71}))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(assetId.toString()))
                .andExpect(jsonPath("$.kind").value("LOGO"));
    }

    @Test
    void generatedMemberRoutePreservesPendingDisableStatus() throws Exception {
        var contexts = mock(TenantContextAccessor.class);
        var disables = mock(MembershipDisableService.class);
        when(contexts.requireCurrent()).thenReturn(CONTEXT);
        when(disables.disable(eq(CONTEXT), eq(MEMBERSHIP), eq(OPERATION), anyString()))
                .thenReturn(new MembershipDisableService.Progress(OPERATION, "REVOKING", "ENABLED", true, true));
        var controller = new MemberDirectoryController(contexts, mock(PermissionAuthority.class),
                mock(JdbcTemplate.class), mock(PlatformTransactionManager.class),
                new MembershipDisableController(contexts, disables),
                mock(MembershipEnableController.class), mock(MembershipOrganizationController.class),
                mock(MembershipRoleController.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new MembershipDisableExceptionHandler()).build();

        mvc.perform(post("/api/v1/members/{membershipId}/disables", MEMBERSHIP)
                        .header("Idempotency-Key", OPERATION)
                        .header("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.operationId").value(OPERATION.toString()))
                .andExpect(jsonPath("$.operationStatus").value("REVOKING"));

        doThrow(new MembershipDisableFailure("MEMBERSHIP_DISABLE_FORBIDDEN"))
                .when(disables).disable(eq(CONTEXT), eq(MEMBERSHIP), eq(OPERATION), anyString());
        mvc.perform(post("/api/v1/members/{membershipId}/disables", MEMBERSHIP)
                        .header("Idempotency-Key", OPERATION)
                        .header("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_DISABLE_FORBIDDEN"));
    }

    @Test
    void generatedInvitationPasswordSetupRouteDelegatesToCredentialAuthority() throws Exception {
        var service = mock(InvitationPasswordSetupService.class);
        String token = "A".repeat(43);
        when(service.request(token, OPERATION))
                .thenReturn(InvitationPasswordSetupGateway.Result.PASSWORD_READY);
        var controller = new InvitationController(mock(TenantContextAccessor.class),
                mock(PermissionAuthority.class), mock(InvitationService.class),
                new InvitationPasswordSetupController(service));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/v1/invitation-password-setups")
                        .header("Idempotency-Key", OPERATION)
                        .header("X-SF-CSRF", "csrf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("PASSWORD_READY"));
    }
}
