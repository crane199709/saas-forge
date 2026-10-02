package io.saas.forge.remotedelivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.saas.forge.sdk.auth.*;
import io.saas.forge.sdk.tenant.*;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManifestAuthorityTest {
    private final UUID actor = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private final UserAccessTokenSignatureVerifier tokens = mock(UserAccessTokenSignatureVerifier.class);
    private final PlatformRoleChecker roles = mock(PlatformRoleChecker.class);
    private final ServiceContextAccessor services = mock(ServiceContextAccessor.class);
    private final TenantContextAccessor tenants = mock(TenantContextAccessor.class);
    private final GrpcTenantContextChecker tenantAuthority = mock(GrpcTenantContextChecker.class);
    private final ManifestAuthority authority = new ManifestAuthority(
            () -> Optional.of(new IdentityContext(actor)), services, tenants, roles, tokens, tenantAuthority);

    @Test
    void requiresCurrentPlatformRoleAndPassesCompleteAuthorizationToVerifier() {
        when(tokens.verify("Bearer signed-token")).thenReturn(claims(null, null));
        when(roles.isAllowed(actor, "PLATFORM_ADMIN")).thenReturn(true);
        assertEquals(actor, authority.administrator("Bearer signed-token"));
        verify(tokens).verify("Bearer signed-token");
        when(roles.isAllowed(actor, "PLATFORM_ADMIN")).thenReturn(false);
        assertRefused(403, "PLATFORM_ADMIN_REQUIRED", () -> authority.administrator("Bearer signed-token"));
        when(roles.isAllowed(actor, "PLATFORM_ADMIN")).thenThrow(new IllegalStateException("unavailable"));
        assertRefused(503, "PLATFORM_AUTHORITY_UNAVAILABLE", () -> authority.administrator("Bearer signed-token"));
    }

    @Test
    void refusesTenantContextBeforeCallingPlatformAuthority() {
        when(tokens.verify("Bearer tenant-token")).thenReturn(claims(actor, actor));
        assertRefused(403, "PLATFORM_CONTEXT_REQUIRED", () -> authority.administrator("Bearer tenant-token"));
        assertRefused(401, "ACCESS_TOKEN_REQUIRED", () -> authority.administrator(null));
        verifyNoInteractions(roles);
    }

    @Test
    void registrationRequiresExactlyTheCiScopeAndTenantReadsRequireContext() {
        when(services.current()).thenReturn(Optional.empty());
        assertRefused(401, "SERVICE_ACCESS_TOKEN_REQUIRED", authority::ci);
        when(services.current()).thenReturn(Optional.of(new ServiceContext(actor,
                Set.of(ManifestPolicy.REGISTER_SCOPE, "notification:send"))));
        assertRefused(403, "CI_CLIENT_REQUIRED", authority::ci);
        when(services.current()).thenReturn(Optional.of(new ServiceContext(actor, Set.of(ManifestPolicy.REGISTER_SCOPE))));
        assertEquals(actor, authority.ci());
        when(tenants.requireCurrent()).thenThrow(new TenantContextUnavailableException());
        assertThrows(TenantContextUnavailableException.class, authority::tenantScope);
        verifyNoInteractions(tenantAuthority);
        var context = new TenantContextSnapshot(actor, actor, actor);
        doReturn(context).when(tenants).requireCurrent();
        doThrow(new ManifestException(403, "TENANT_CONTEXT_NOT_USABLE"))
                .when(tenantAuthority).requireUsable(context);
        assertRefused(403, "TENANT_CONTEXT_NOT_USABLE", authority::tenantScope);
    }

    private VerifiedUserAccessTokenClaims claims(UUID membership, UUID tenant) {
        return new VerifiedUserAccessTokenClaims(actor, actor, "key", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(300), membership, tenant);
    }

    private void assertRefused(int status, String code, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(ManifestException.class, action);
        assertEquals(status, error.status());
        assertEquals(code, error.code());
    }
}
