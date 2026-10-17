package io.saas.forge.iam.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saas.forge.iam.application.authentication.ConsoleSessionService;
import io.saas.forge.iam.application.authentication.InvitationAcceptanceGateway;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class InvitationAcceptanceControllerTest {
    @Test
    void generatedConsoleRouteAcceptsThroughTheCurrentBrowserSession() throws Exception {
        var sessions = mock(ConsoleSessionService.class);
        var acceptance = mock(InvitationAcceptanceGateway.class);
        UUID identity = UUID.fromString("0198c9d5-0f25-7000-8000-000000000101");
        UUID key = UUID.fromString("0198c9d5-0f25-7000-8000-000000000102");
        UUID membership = UUID.fromString("0198c9d5-0f25-7000-8000-000000000103");
        UUID tenant = UUID.fromString("0198c9d5-0f25-7000-8000-000000000104");
        String token = "A".repeat(43);
        when(sessions.invitationIdentity("slot", "refresh", "\"2\"")).thenReturn(identity);
        when(acceptance.accept(eq(token), eq(identity), eq(key), anyString()))
                .thenReturn(new InvitationAcceptanceGateway.Result(membership, tenant, false));
        var http = MockMvcBuilders.standaloneSetup(new InvitationAcceptanceController(sessions, acceptance))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()))
                .build();

        http.perform(post("/api/v2/auth/invitation-acceptances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SF-CSRF", "1")
                        .header("If-Match", "\"2\"")
                        .header("Idempotency-Key", key)
                        .cookie(new Cookie(ConsoleAuthenticationController.SLOT_COOKIE, "slot"),
                                new Cookie(ConsoleAuthenticationController.REFRESH_COOKIE, "refresh"))
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(membership.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenant.toString()))
                .andExpect(jsonPath("$.replayed").value(false));
    }
}
