package io.saas.forge.tenantaccess.domain.invitation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvitationTest {
    private static final Instant CREATED = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void resendRotatesCredentialWithoutExtendingSevenDayExpiry() {
        Invitation original = Invitation.create(UUID.randomUUID(), UUID.randomUUID(),
                "member@example.com", "old-digest", CREATED);
        Invitation resent = original.resend("new-digest", CREATED.plusSeconds(3600));

        assertEquals(CREATED.plusSeconds(7 * 24 * 3600), resent.expiresAt());
        assertEquals("new-digest", resent.tokenHash());
        assertEquals("PENDING", resent.status().name());
        assertThrows(InvitationUnavailableException.class,
                () -> resent.resend("later-digest", resent.expiresAt()));
    }

    @Test
    void revokedInvitationCannotBeResent() {
        Invitation original = Invitation.create(UUID.randomUUID(), UUID.randomUUID(),
                "member@example.com", "digest", CREATED);
        Invitation revoked = original.revoke(CREATED.plusSeconds(1));

        assertEquals("REVOKED", revoked.status().name());
        assertThrows(InvitationUnavailableException.class,
                () -> revoked.resend("new-digest", CREATED.plusSeconds(2)));
    }
}
