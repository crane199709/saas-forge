package io.saas.forge.iam.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.iam.application.authorization.PlatformAdminGrantResult;
import io.saas.forge.iam.application.authorization.PlatformAdminGrantService;
import io.saas.forge.iam.application.authorization.PlatformAdminQueries;
import io.saas.forge.iam.application.authorization.PlatformAdminRevokeService;
import io.saas.forge.iam.application.client.OAuthClientManagementAuthorizer;
import io.saas.forge.iam.contract.model.PlatformAdminGrantRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PlatformAdministratorsControllerTest {
    @Test
    void authorizedOperatorCanListGrantAndRevokeWithNoStoreResponses() {
        UUID actor = UUID.fromString("019a0000-0000-7000-8000-000000000001");
        UUID identity = UUID.fromString("019a0000-0000-7000-8000-000000000002");
        UUID assignment = UUID.fromString("019a0000-0000-7000-8000-000000000003");
        UUID requestId = UUID.fromString("019a0000-0000-7000-8000-000000000004");
        String bearer = "Bearer authorized-test-token";
        var authorizer = mock(OAuthClientManagementAuthorizer.class);
        var grants = mock(PlatformAdminGrantService.class);
        var queries = mock(PlatformAdminQueries.class);
        var revokes = mock(PlatformAdminRevokeService.class);
        when(authorizer.authorize(bearer)).thenReturn(actor);
        when(queries.list()).thenReturn(List.of(new PlatformAdminQueries.PlatformAdminEntry(
                identity, assignment, "admin@example.test", true, Instant.parse("2026-10-15T00:00:00Z"))));
        when(grants.grant(actor, requestId, "new@example.test", null)).thenReturn(
                new PlatformAdminGrantResult(identity, assignment, true,
                        PlatformAdminGrantResult.SetupState.PASSWORD_READY));
        var controller = new PlatformAdministratorsController(authorizer, grants, queries, revokes);
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, bearer);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var listed = controller.listPlatformAdministrators();
            assertEquals(200, listed.getStatusCode().value());
            assertEquals("no-store", listed.getHeaders().getCacheControl());
            assertNotNull(listed.getBody());
            var item = listed.getBody().getItems().get(0);
            assertEquals(identity, item.getIdentityId());
            assertEquals(assignment, item.getRoleAssignmentId());
            assertEquals("admin@example.test", item.getEmail());
            assertEquals(OffsetDateTime.parse("2026-10-15T00:00:00Z"), item.getAssignedAt());

            var granted = controller.grantPlatformAdministrator(requestId,
                    new PlatformAdminGrantRequest("new@example.test"));
            assertEquals(200, granted.getStatusCode().value());
            assertEquals("no-store", granted.getHeaders().getCacheControl());
            assertNotNull(granted.getBody());
            assertEquals(identity, granted.getBody().getIdentityId());
            assertEquals(assignment, granted.getBody().getRoleAssignmentId());
            assertEquals("PASSWORD_READY", granted.getBody().getSetupState().getValue());

            var revoked = controller.revokePlatformAdministrator(identity, requestId);
            assertEquals(204, revoked.getStatusCode().value());
            assertEquals("no-store", revoked.getHeaders().getCacheControl());
            assertFalse(revoked.hasBody());
            verify(revokes).revoke(actor, identity, requestId, null);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
