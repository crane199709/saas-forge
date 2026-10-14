package io.saas.forge.tenantaccess.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saas.forge.tenantaccess.application.authorization.PlatformAdminAuthorizer;
import io.saas.forge.tenantaccess.application.brand.BrandAssetPolicyService;
import io.saas.forge.tenantaccess.application.brand.BrandAssetService;
import io.saas.forge.tenantaccess.application.brand.BrandPng;
import io.saas.forge.tenantaccess.application.brand.DisabledBrandAsset;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BrandAssetPolicyControllerTest {
    private static final UUID ASSET = UUID.fromString("019535d9-0001-7000-8000-000000000028");
    private static final UUID TENANT = UUID.fromString("019535d9-0001-7000-8000-000000000003");
    private static final UUID ACTOR = UUID.fromString("019535d9-0001-7000-8000-000000000001");

    @Test
    void platformAuthorizationPrecedesDisableAndResponseDoesNotEditProfile() throws Exception {
        var admins = mock(PlatformAdminAuthorizer.class);
        var service = mock(BrandAssetPolicyService.class);
        when(admins.authorize("Bearer platform-token")).thenReturn(ACTOR);
        when(service.disable(ACTOR, ASSET)).thenReturn(new DisabledBrandAsset(ASSET, TENANT,
                BrandPng.Kind.LOGO, Instant.parse("2026-10-14T08:00:00Z"), true));
        var mvc = MockMvcBuilders.standaloneSetup(new BrandAssetPolicyController(admins, service))
                .setControllerAdvice(new BrandAssetExceptionHandler()).build();
        mvc.perform(post("/api/v1/platform/brand-assets/{assetId}/disable", ASSET)
                        .header("Authorization", "Bearer platform-token").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(ASSET.toString()))
                .andExpect(jsonPath("$.tenantId").value(TENANT.toString()))
                .andExpect(jsonPath("$.kind").value("LOGO"))
                .andExpect(jsonPath("$.profile").doesNotExist());
    }

    @Test
    void missingAssetReturnsNoPolicySuccess() throws Exception {
        var admins = mock(PlatformAdminAuthorizer.class);
        var service = mock(BrandAssetPolicyService.class);
        when(admins.authorize("Bearer platform-token")).thenReturn(ACTOR);
        when(service.disable(ACTOR, ASSET)).thenThrow(new BrandAssetService.Missing());
        var mvc = MockMvcBuilders.standaloneSetup(new BrandAssetPolicyController(admins, service))
                .setControllerAdvice(new BrandAssetExceptionHandler()).build();
        mvc.perform(post("/api/v1/platform/brand-assets/{assetId}/disable", ASSET)
                        .header("Authorization", "Bearer platform-token").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRAND_ASSET_NOT_FOUND"));
    }
}
