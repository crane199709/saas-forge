package io.saas.forge.iam.domain.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RevocationFenceTargetBoundaryTest {
    private static final UUID IDENTITY = UUID.fromString("019535d9-0001-7000-8000-000000000001");
    private static final UUID TENANT = UUID.fromString("019535d9-0001-7000-8000-000000000002");
    private static final UUID MEMBERSHIP = UUID.fromString("019535d9-0001-7000-8000-000000000003");

    @Test
    void fenceTargetsKeepTheExactIdentityTenantOrMembershipAuthority() {
        assertEquals(IDENTITY, RevocationFenceTarget.identity(IDENTITY).targetId());
        assertEquals(TENANT, RevocationFenceTarget.tenant(TENANT).targetId());
        assertEquals(MEMBERSHIP, RevocationFenceTarget.membership(MEMBERSHIP, TENANT).targetId());
        assertThrows(IllegalArgumentException.class, () -> RevocationFenceTarget.identity(null));
        assertThrows(IllegalArgumentException.class, () -> RevocationFenceTarget.tenant(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> RevocationFenceTarget.membership(null, TENANT));
        assertThrows(IllegalArgumentException.class, () -> RevocationFenceTarget.membership(MEMBERSHIP, null));
    }

    @Test
    void malformedCombinedTargetsCannotWidenARevocationFence() {
        assertThrows(IllegalArgumentException.class, () -> new RevocationFenceTarget(
                null, null, null, IDENTITY));
        assertThrows(IllegalArgumentException.class, () -> new RevocationFenceTarget(
                RevocationFenceTargetType.IDENTITY, MEMBERSHIP, TENANT, IDENTITY));
        assertThrows(IllegalArgumentException.class, () -> new RevocationFenceTarget(
                RevocationFenceTargetType.TENANT, MEMBERSHIP, TENANT, null));
        assertThrows(IllegalArgumentException.class, () -> new RevocationFenceTarget(
                RevocationFenceTargetType.MEMBERSHIP, MEMBERSHIP, TENANT, IDENTITY));
        assertThrows(IllegalArgumentException.class, () -> new RevocationFenceTarget(
                RevocationFenceTargetType.MEMBERSHIP, UUID.randomUUID(), TENANT, null));
    }
}
