package io.saas.forge.tenantaccess.domain.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantUpdateTest {
    private static final UUID ID = UUID.fromString("019535d9-0000-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void expiredActiveTenantCanBeExtendedOrClearedWithoutChangingItsLifecycle() {
        Tenant expired = new Tenant(ID, "Old", TenantStatus.ACTIVE, NOW.minusSeconds(1), NOW.minusSeconds(60), NOW.minusSeconds(60));

        Tenant extended = expired.updateDetails("New", NOW.plusSeconds(3600), NOW);
        Tenant unlimited = expired.updateDetails("New", null, NOW);

        assertEquals(TenantStatus.ACTIVE, extended.status());
        assertEquals(NOW.plusSeconds(3600), extended.expiresAt());
        assertNull(unlimited.expiresAt());
        assertEquals("New", unlimited.displayName());
    }

    @Test
    void suspendedTenantIsNotResumedAndClosedTenantCannotBeReopened() {
        Tenant suspended = new Tenant(ID, "Old", TenantStatus.SUSPENDED, null, NOW.minusSeconds(60), NOW.minusSeconds(60));
        Tenant closed = new Tenant(ID, "Old", TenantStatus.CLOSED, null, NOW.minusSeconds(60), NOW.minusSeconds(60));

        assertEquals(TenantStatus.SUSPENDED, suspended.updateDetails("New", NOW.plusSeconds(60), NOW).status());
        assertThrows(TenantStateTransitionNotAllowedException.class,
                () -> closed.updateDetails("New", NOW.plusSeconds(60), NOW));
        assertThrows(TenantExpiryInvalidException.class,
                () -> suspended.updateDetails("New", NOW, NOW));
    }
}
