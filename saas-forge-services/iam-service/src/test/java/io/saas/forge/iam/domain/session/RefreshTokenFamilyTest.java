package io.saas.forge.iam.domain.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenFamilyTest {

    @Test
    void workViewSwitchKeepsIdentityAndSessionDeadlinesAcrossPlatformAndTenant() {
        Instant loginAt = Instant.parse("2026-08-20T00:00:00Z");
        RefreshTokenFamily original = RefreshTokenFamily.start(UUID.randomUUID(),
                RefreshTokenFamilyPurpose.USER_TENANT_SELECTION, null, null, loginAt)
                .identifiedBy(UUID.randomUUID());
        UUID membership = UUID.randomUUID(), tenant = UUID.randomUUID();
        RefreshTokenFamily selected = original.selectWorkContext(
                RefreshTokenFamilyPurpose.USER_TENANT, membership, tenant);
        assertSame(selected, selected.selectWorkContext(RefreshTokenFamilyPurpose.USER_TENANT, membership, tenant));
        RefreshTokenFamily platform = selected.selectWorkContext(RefreshTokenFamilyPurpose.USER_PLATFORM, null, null);
        assertEquals(original.id(), platform.id());
        assertEquals(original.identityId(), platform.identityId());
        assertEquals(original.lastUsedAt(), platform.lastUsedAt());
        assertEquals(original.absoluteExpiresAt(), platform.absoluteExpiresAt());
        assertEquals(2, platform.contextVersion());
        assertEquals(RefreshTokenFamilyPurpose.USER_PLATFORM, platform.purpose());
        assertEquals(null, platform.membershipId());
        assertEquals(null, platform.tenantId());
    }

    @Test
    void workViewSwitchRejectsRestrictedSessionsAndIncompleteTargets() {
        Instant loginAt = Instant.parse("2026-08-20T00:00:00Z");
        RefreshTokenFamily restricted = RefreshTokenFamily.startInitialPasswordChange(
                UUID.randomUUID(), UUID.randomUUID(), loginAt, loginAt.plusSeconds(600));
        assertThrows(IllegalStateException.class,
                () -> restricted.selectWorkContext(RefreshTokenFamilyPurpose.USER_PLATFORM, null, null));
        RefreshTokenFamily platform = RefreshTokenFamily.start(UUID.randomUUID(), null, null, loginAt);
        assertThrows(IllegalArgumentException.class,
                () -> platform.selectWorkContext(RefreshTokenFamilyPurpose.USER_TENANT, UUID.randomUUID(), null));
        assertThrows(IllegalArgumentException.class,
                () -> platform.selectWorkContext(RefreshTokenFamilyPurpose.USER_PLATFORM, null, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> platform.selectWorkContext(RefreshTokenFamilyPurpose.USER_TENANT_SELECTION, null, null));
    }

    @Test
    void refreshKeepsOriginalAbsoluteExpiryAndCarriesNewContext() {
        Instant loginAt = Instant.parse("2026-08-20T00:00:00Z");
        UUID identityId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        RefreshTokenFamily family = RefreshTokenFamily.start(identityId, null, null, loginAt);

        RefreshTokenFamily refreshed = family.recordUse(membershipId, tenantId, loginAt.plus(29, ChronoUnit.MINUTES));

        assertEquals(loginAt.plus(8, ChronoUnit.HOURS), refreshed.absoluteExpiresAt());
        assertEquals(membershipId, refreshed.membershipId());
        assertEquals(tenantId, refreshed.tenantId());
        assertTrue(refreshed.isUsableAt(loginAt.plus(30, ChronoUnit.MINUTES)));
        assertFalse(refreshed.isUsableAt(loginAt.plus(8, ChronoUnit.HOURS)));
    }

    @Test
    void activityKeepsContextVersionWhileContextSelectionAdvancesIt() {
        Instant loginAt = Instant.parse("2026-08-20T00:00:00Z");
        RefreshTokenFamily platform = RefreshTokenFamily.start(UUID.randomUUID(), null, null, loginAt);
        RefreshTokenFamily active = platform.recordUse(null, null, loginAt.plusSeconds(1));
        RefreshTokenFamily selection = RefreshTokenFamily.start(
                UUID.randomUUID(), RefreshTokenFamilyPurpose.USER_TENANT_SELECTION,
                null, null, loginAt);

        RefreshTokenFamily selected = selection.selectTenant(
                UUID.randomUUID(), UUID.randomUUID(), loginAt.plusSeconds(1));

        assertEquals(0, platform.contextVersion());
        assertEquals(0, active.contextVersion());
        assertEquals(1, selected.contextVersion());
    }

    @Test
    void idleFamilyCannotBeUsedOrResurrected() {
        Instant loginAt = Instant.parse("2026-08-20T00:00:00Z");
        RefreshTokenFamily family = RefreshTokenFamily.start(UUID.randomUUID(), null, null, loginAt);

        assertThrows(IllegalStateException.class,
                () -> family.recordUse(null, null, loginAt.plus(30, ChronoUnit.MINUTES)));
    }

    @Test
    void initialPasswordChangeExpiresAtEarlierOfTenMinutesAndCredentialExpiry() {
        Instant loginAt = Instant.parse("2026-08-20T00:00:00Z");
        RefreshTokenFamily tenMinutes = RefreshTokenFamily.startInitialPasswordChange(
                UUID.randomUUID(), UUID.randomUUID(), loginAt, loginAt.plusSeconds(3_600));
        RefreshTokenFamily credentialBound = RefreshTokenFamily.startInitialPasswordChange(
                UUID.randomUUID(), UUID.randomUUID(), loginAt, loginAt.plusSeconds(300));

        assertEquals(loginAt.plusSeconds(600), tenMinutes.absoluteExpiresAt());
        assertEquals(loginAt.plusSeconds(300), credentialBound.absoluteExpiresAt());
        assertEquals(RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE, credentialBound.purpose());
        assertFalse(credentialBound.isUsableAt(loginAt.plusSeconds(300)));
    }

    @Test
    void rejectsIncompleteContextAndRevocationCanOnlyMoveForward() {
        Instant loginAt = Instant.parse("2026-08-20T00:00:00Z");
        UUID identityId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> RefreshTokenFamily.start(identityId, UUID.randomUUID(), null, loginAt));
        assertThrows(IllegalArgumentException.class,
                () -> RefreshTokenFamily.restore(null, identityId, RefreshTokenFamilyPurpose.USER_PLATFORM,
                        null, null, null, 0, loginAt, loginAt.plusSeconds(1), null));
        assertThrows(IllegalArgumentException.class,
                () -> RefreshTokenFamily.restore(UUID.randomUUID(), identityId, RefreshTokenFamilyPurpose.USER_PLATFORM,
                        null, null, null, 0, loginAt, loginAt, null));
        assertThrows(IllegalArgumentException.class,
                () -> RefreshTokenFamily.restore(UUID.randomUUID(), identityId, RefreshTokenFamilyPurpose.USER_PLATFORM,
                        null, null, null, -1, loginAt, loginAt.plusSeconds(1), null));

        RefreshTokenFamily pending = RefreshTokenFamily.start(identityId, null, null, loginAt);
        assertThrows(IllegalStateException.class, () -> pending.identifiedBy(null));
        RefreshTokenFamily stored = pending.identifiedBy(UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> stored.identifiedBy(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> stored.revoke(null));

        RefreshTokenFamily revoked = stored.revoke(loginAt.plusSeconds(1));
        assertSame(revoked, revoked.revoke(loginAt.plusSeconds(2)));
        assertFalse(revoked.isUsableAt(loginAt.plusSeconds(2)));
        assertThrows(IllegalStateException.class, () -> revoked.requireUsableAt(loginAt.plusSeconds(2)));
    }
}
