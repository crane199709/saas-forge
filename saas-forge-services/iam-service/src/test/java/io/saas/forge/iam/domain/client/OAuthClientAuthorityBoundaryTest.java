package io.saas.forge.iam.domain.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OAuthClientAuthorityBoundaryTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final UUID CLIENT_ID = UUID.fromString("019535d9-0001-7000-8000-000000000001");

    @Test
    void managementCannotMintReservedServiceAuthorityOrMixedCiScopes() {
        Set<OAuthScope> iamScopes = Set.of(OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ,
                OAuthScope.TENANT_ACCESS_INVITATION_ACCEPT);
        var reserved = OAuthClient.register("iam service", iamScopes, NOW);
        assertEquals(OAuthClientType.RESERVED_SERVICE, reserved.clientType());
        assertEquals(ReservedServiceKey.IAM, reserved.reservedServiceKey());
        assertThrows(OAuthClientScopeGrantForbiddenException.class,
                () -> OAuthClient.registerManaged("forged iam", iamScopes, NOW));
        assertThrows(OAuthClientScopeGrantForbiddenException.class,
                () -> OAuthClient.registerRuntime("forged iam", iamScopes, NOW));
        assertThrows(OAuthClientScopeGrantForbiddenException.class,
                () -> OAuthClient.registerManaged("mixed ci",
                        Set.of(OAuthScope.REMOTE_MANIFEST_REGISTER, OAuthScope.RUNTIME_READ), NOW));
        var runtime = OAuthClient.registerManaged("runtime worker", Set.of(OAuthScope.RUNTIME_READ), NOW);
        assertEquals(OAuthClientType.RUNTIME_SERVICE, runtime.clientType());
    }

    @Test
    void persistedClientCannotChangeTypeOrServiceKeyToGainScopes() {
        Set<OAuthScope> runtimeScopes = Set.of(OAuthScope.RUNTIME_READ);
        assertThrows(OAuthClientScopeGrantForbiddenException.class, () -> OAuthClient.restore(CLIENT_ID,
                "forged", OAuthClientType.RUNTIME_SERVICE, ReservedServiceKey.IAM, runtimeScopes,
                OAuthClientStatus.ACTIVE, NOW, NOW, null));
        assertThrows(OAuthClientScopeGrantForbiddenException.class, () -> OAuthClient.restore(CLIENT_ID,
                "forged", OAuthClientType.CI_CLIENT, null, runtimeScopes,
                OAuthClientStatus.ACTIVE, NOW, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> OAuthClient.restore(CLIENT_ID,
                "forged", OAuthClientType.RESERVED_SERVICE, ReservedServiceKey.IAM, runtimeScopes,
                OAuthClientStatus.ACTIVE, NOW, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> OAuthClient.restore(CLIENT_ID,
                "worker", OAuthClientType.RUNTIME_SERVICE, null, runtimeScopes,
                OAuthClientStatus.REVOKED, NOW, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> OAuthClient.restore(CLIENT_ID,
                "worker", OAuthClientType.RUNTIME_SERVICE, null, runtimeScopes,
                OAuthClientStatus.ACTIVE, NOW, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> OAuthClient.restore(CLIENT_ID,
                "worker", OAuthClientType.RUNTIME_SERVICE, null, runtimeScopes,
                OAuthClientStatus.ACTIVE, NOW, NOW.minusSeconds(1), null));
    }

    @Test
    void revokedClientCannotBeReactivatedAndRepeatedRevocationKeepsOriginalTime() {
        var active = OAuthClient.registerManaged("worker", Set.of(OAuthScope.RUNTIME_READ), NOW)
                .identifiedBy(CLIENT_ID);
        active.requireActive();
        var revoked = active.revoke(NOW.plusSeconds(1));
        assertEquals(NOW.plusSeconds(1), revoked.revokedAt());
        assertSame(revoked, revoked.revoke(NOW.plusSeconds(2)));
        assertThrows(IllegalStateException.class, revoked::requireActive);
        assertThrows(IllegalArgumentException.class, () -> active.revoke(null));
    }
}
