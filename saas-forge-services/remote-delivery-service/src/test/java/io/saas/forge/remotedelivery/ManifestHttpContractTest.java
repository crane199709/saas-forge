package io.saas.forge.remotedelivery;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class ManifestHttpContractTest {
    @Test void generatedMappingReturnsFormalNullFieldsAndTenantProjection() throws Exception {
        var authority = mock(ManifestAuthority.class);var service = mock(ManifestService.class);
        var actor = UUID.fromString("018f2d3a-4b5c-7d6e-8f90-123456789abc");
        var id = UUID.fromString("018f2d3a-4b5c-7d6e-8f90-123456789abd");
        var value = new RemoteManifest(id,"project","1.0.0","https://remote.saas.forge.test/project/1.0.0/remote.js","1.4.0","a".repeat(64),RemoteManifest.State.PENDING_REVIEW,actor,Instant.parse("2026-10-02T12:00:00Z"),null,null,null,null);
        when(authority.administrator(any())).thenReturn(actor);when(authority.tenantScope()).thenReturn("tenant");
        when(service.list(null,20,actor.toString(),false)).thenReturn(new ManifestService.ManifestPage(List.of(value),null,false));
        when(service.list(null,20,"tenant",true)).thenReturn(new ManifestService.ManifestPage(List.of(value),null,false));
        var request = new MockHttpServletRequest();request.addHeader("Authorization","Bearer fixture");
        var validation = new org.springframework.validation.beanvalidation.MethodValidationPostProcessor();
        validation.setProxyTargetClass(true);
        validation.afterPropertiesSet();
        var controller = validation.postProcessAfterInitialization(
                new ManifestController(authority,service,request,JsonMapper.builder().build()), "manifestController");
        org.junit.jupiter.api.Assertions.assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(controller));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ManifestProblemHandler()).build();
        mvc.perform(get("/api/v1/platform/remote-manifests")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].registeredAt").value("2026-10-02T12:00:00Z"))
                .andExpect(jsonPath("$.items[0].reviewedBy").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/remote-manifests/enabled")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].source").value(value.source()))
                .andExpect(jsonPath("$.items[0].registeredBy").doesNotExist())
                .andExpect(jsonPath("$.items[0].reviewedBy").doesNotExist());
        mvc.perform(post("/api/v1/platform/remote-manifests/"+id+"/approve")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/platform/remote-manifests").param("limit", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.type").value("urn:saas.forge:problem:validation-failed"));
        when(authority.administrator(any())).thenThrow(new ManifestException(403,"PLATFORM_ADMIN_REQUIRED"));
        mvc.perform(get("/api/v1/platform/remote-manifests"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:saas.forge:problem:platform-admin-required"));
        verify(service,never()).decide(any(),any(),any(),any());
    }
}
