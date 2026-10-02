package io.saas.forge.iam.domain.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OAuthClientDomainTest {

    @Test
    void ciScopeCannotBeMixedWithRuntimeOrInternalAuthority() {
        var ci = OAuthClient.registerManaged("project CI",Set.of(OAuthScope.REMOTE_MANIFEST_REGISTER),Instant.now());
        assertEquals(OAuthClientType.CI_CLIENT,ci.clientType());
        assertEquals(null,ci.reservedServiceKey());
        assertThrows(OAuthClientScopeGrantForbiddenException.class, () -> OAuthClient.registerManaged("mixed",Set.of(OAuthScope.REMOTE_MANIFEST_REGISTER,OAuthScope.RUNTIME_READ),Instant.now()));
        assertThrows(OAuthClientScopeGrantForbiddenException.class, () -> OAuthClient.registerRuntime("runtime",Set.of(OAuthScope.REMOTE_MANIFEST_REGISTER),Instant.now()));
        assertThrows(OAuthClientScopeGrantForbiddenException.class, () -> OAuthClient.registerManaged("internal",Set.of(OAuthScope.IAM_PLATFORM_ROLE_READ),Instant.now()));
        var remote = OAuthClient.register("remote-delivery-service",Set.of(OAuthScope.IAM_PLATFORM_ROLE_READ,OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ,OAuthScope.TENANT_ACCESS_TENANT_READ),Instant.now());
        assertEquals(ReservedServiceKey.REMOTE_DELIVERY,remote.reservedServiceKey());
    }

    @Test
    void onlyMvpScopesCanBeUsed() {
        OAuthClient client = OAuthClient.register("runtime worker", Set.of(OAuthScope.RUNTIME_READ), Instant.now());

        assertTrue(client.allowedScopes().contains(OAuthScope.RUNTIME_READ));
        assertSame(OAuthScope.IAM_SESSIONS_WRITE, OAuthScope.fromValue("iam:sessions:write"));
        assertThrows(IllegalArgumentException.class, () -> OAuthScope.fromValue("tenant:write"));
    }

    @Test
    void secretCanEnterOnlyOneOverlapWindowAndClientRevocationIsTerminal() {
        Instant issuedAt = Instant.parse("2026-08-20T00:00:00Z");
        ClientSecret secret = ClientSecret.issued(UUID.randomUUID(), issuedAt);
        ClientSecret overlapping = secret.overlapUntil(issuedAt.plusSeconds(1));

        assertTrue(overlapping.isValidAt(issuedAt.plus(23, ChronoUnit.HOURS)));
        assertFalse(overlapping.isValidAt(issuedAt.plus(25, ChronoUnit.HOURS)));
        assertThrows(IllegalStateException.class, () -> overlapping.overlapUntil(issuedAt.plusSeconds(2)));
    }

    @Test
    void rejectsClientAndSecretTransitionsOutsideTheirLifecycle() {
        Instant issuedAt = Instant.parse("2026-08-20T00:00:00Z");
        UUID clientId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> OAuthClient.register(" ", Set.of(OAuthScope.RUNTIME_READ), issuedAt));
        assertThrows(IllegalArgumentException.class, () -> OAuthClient.register("worker", Set.of(), issuedAt));
        assertThrows(IllegalArgumentException.class,
                () -> OAuthClient.restore(null, "worker", Set.of(OAuthScope.RUNTIME_READ),
                        OAuthClientStatus.ACTIVE, issuedAt, null));

        OAuthClient pending = OAuthClient.register("worker", Set.of(OAuthScope.RUNTIME_READ), issuedAt);
        assertThrows(IllegalStateException.class, () -> pending.identifiedBy(null));
        OAuthClient stored = pending.identifiedBy(clientId);
        assertThrows(IllegalStateException.class, () -> stored.identifiedBy(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> stored.revoke(issuedAt.minusSeconds(1)));

        OAuthClient revoked = stored.revoke(issuedAt.plusSeconds(1));
        assertSame(revoked, revoked.revoke(issuedAt.plusSeconds(2)));
        assertThrows(IllegalStateException.class, revoked::requireActive);
        assertThrows(IllegalArgumentException.class,
                () -> ClientSecret.restore(null, clientId, issuedAt, null, null));
    }

    @Test
    void secretRevocationPreservesItsOriginalTimestamp() {
        Instant issuedAt = Instant.parse("2026-08-20T00:00:00Z");
        ClientSecret pending = ClientSecret.issued(UUID.randomUUID(), issuedAt);

        assertThrows(IllegalStateException.class, () -> pending.identifiedBy(null));
        ClientSecret stored = pending.identifiedBy(UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> stored.identifiedBy(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> stored.revoke(null));

        ClientSecret revoked = stored.revoke(issuedAt.plusSeconds(1));
        assertEquals(revoked.revokedAt(), revoked.revoke(issuedAt.plusSeconds(2)).revokedAt());
        assertFalse(revoked.isValidAt(issuedAt.plusSeconds(2)));
    }
}
