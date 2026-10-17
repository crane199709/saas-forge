package io.saas.forge.tenantaccess.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.brand.BrandProfileChange;
import io.saas.forge.tenantaccess.application.brand.BrandProfileRevision;
import io.saas.forge.tenantaccess.application.brand.BrandProfileService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BrandProfileControllerTest {
    private static final TenantContextSnapshot CONTEXT = new TenantContextSnapshot(
            UUID.fromString("019535d9-0001-7000-8000-000000000001"),
            UUID.fromString("019535d9-0001-7000-8000-000000000002"),
            UUID.fromString("019535d9-0001-7000-8000-000000000003"));
    private static final BrandProfileChange CHANGE = new BrandProfileChange("Acme",
            "/brands/019535d9-0001-7000-8000-000000000011",
            "/brands/019535d9-0001-7000-8000-000000000012", "#123456", "#234567");

    @Test
    void versionedGetAndPutReturnAuthoritativeProfileWithoutCache() throws Exception {
        var contexts = mock(TenantContextAccessor.class);
        var service = mock(BrandProfileService.class);
        when(contexts.requireCurrent()).thenReturn(CONTEXT);
        when(service.read(CONTEXT)).thenReturn(new BrandProfileRevision(0, null));
        when(service.save(any(), anyLong(), any())).thenReturn(new BrandProfileRevision(1, CHANGE));
        var mvc = MockMvcBuilders.standaloneSetup(new BrandProfileController(contexts, service))
                .setControllerAdvice(new BrandProfileExceptionHandler()).build();
        mvc.perform(get("/api/v1/brand-profile"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.revision").value(0));
        mvc.perform(put("/api/v1/brand-profile").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"displayName":"Acme","logoUrl":"/brands/019535d9-0001-7000-8000-000000000011",
                                "faviconUrl":"/brands/019535d9-0001-7000-8000-000000000012",
                                "primaryColor":"#123456","accentColor":"#234567"}
                                """))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.profile.displayName").value("Acme"));
    }

    @Test
    void missingRevisionIsRejectedBeforeAnyBusinessWrite() throws Exception {
        var contexts = mock(TenantContextAccessor.class);
        var service = mock(BrandProfileService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new BrandProfileController(contexts, service))
                .setControllerAdvice(new BrandProfileExceptionHandler()).build();
        mvc.perform(put("/api/v1/brand-profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("BRAND_PROFILE_PRECONDITION_REQUIRED"))
                .andExpect(jsonPath("$.type").value("urn:saas.forge:problem:brand-profile-precondition-required"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void staleRevisionReturnsStableConflictCode() throws Exception {
        var contexts = mock(TenantContextAccessor.class);
        var service = mock(BrandProfileService.class);
        when(contexts.requireCurrent()).thenReturn(CONTEXT);
        doThrow(new BrandProfileService.Stale()).when(service).save(any(), anyLong(), any());
        var mvc = MockMvcBuilders.standaloneSetup(new BrandProfileController(contexts, service))
                .setControllerAdvice(new BrandProfileExceptionHandler()).build();
        mvc.perform(put("/api/v1/brand-profile").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"displayName":"Acme","logoUrl":"/brands/019535d9-0001-7000-8000-000000000011",
                                "faviconUrl":"/brands/019535d9-0001-7000-8000-000000000012",
                                "primaryColor":"#123456","accentColor":"#234567"}
                                """))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("BRAND_PROFILE_STALE"))
                .andExpect(jsonPath("$.type").value("urn:saas.forge:problem:brand-profile-stale"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
