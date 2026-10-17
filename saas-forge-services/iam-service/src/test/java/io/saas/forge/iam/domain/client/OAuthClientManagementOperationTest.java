package io.saas.forge.iam.domain.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saas.forge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OAuthClientManagementOperationTest {
    private static final UUID OPERATION = UUID.fromString("019535d9-0001-7000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("019535d9-0001-7000-8000-000000000002");
    private static final UUID KEY = UUID.fromString("019535d9-0001-7000-8000-000000000003");
    private static final UUID CLIENT = UUID.fromString("019535d9-0001-7000-8000-000000000004");
    private static final UUID ORIGINAL = UUID.fromString("019535d9-0001-7000-8000-000000000005");
    private static final UUID SECRET = UUID.fromString("019535d9-0001-7000-8000-000000000006");
    private static final Sha256Digest FINGERPRINT = Sha256Digest.of(new byte[32]);
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    @Test
    void successfulManagementFactsKeepOnlyTheReferencesNeededForTheirOperation() {
        assertEquals(201, fact("CREATE", null, SECRET, 201).httpStatus());
        assertEquals(200, fact("ROTATE", null, SECRET, 200).httpStatus());
        assertEquals(200, fact("RECOVER", ORIGINAL, SECRET, 200).httpStatus());
        assertEquals(204, fact("REVOKE", null, null, 204).httpStatus());
    }

    @Test
    void recoveryCannotClaimAnotherOutcomeOrInventASecretOnRevocation() {
        assertThrows(IllegalArgumentException.class, () -> fact("RECOVER", null, SECRET, 200));
        assertThrows(IllegalArgumentException.class, () -> fact("RECOVER", ORIGINAL, null, 200));
        assertThrows(IllegalArgumentException.class, () -> fact("REVOKE", null, SECRET, 204));
        assertThrows(IllegalArgumentException.class, () -> fact("REVOKE", ORIGINAL, null, 204));
        assertThrows(IllegalArgumentException.class, () -> fact("CREATE", ORIGINAL, SECRET, 201));
        assertThrows(IllegalArgumentException.class, () -> fact("ROTATE", null, null, 200));
        assertThrows(IllegalArgumentException.class, () -> fact("CREATE", null, SECRET, 200));
        assertThrows(IllegalArgumentException.class, () -> fact("RECOVER", ORIGINAL, SECRET, 204));
        assertThrows(IllegalArgumentException.class, () -> fact("UNKNOWN", null, SECRET, 200));
    }

    @Test
    void replayRecordRequiresStableActorKeyFingerprintAndSucceededOutcome() {
        assertThrows(IllegalArgumentException.class, () -> new OAuthClientManagementOperation(
                null, ACTOR, KEY, "REVOKE", CLIENT, FINGERPRINT, null, null, "SUCCEEDED", 204, NOW));
        assertThrows(IllegalArgumentException.class, () -> new OAuthClientManagementOperation(
                OPERATION, null, KEY, "REVOKE", CLIENT, FINGERPRINT, null, null, "SUCCEEDED", 204, NOW));
        assertThrows(IllegalArgumentException.class, () -> new OAuthClientManagementOperation(
                OPERATION, ACTOR, UUID.randomUUID(), "REVOKE", CLIENT, FINGERPRINT,
                null, null, "SUCCEEDED", 204, NOW));
        assertThrows(IllegalArgumentException.class, () -> new OAuthClientManagementOperation(
                OPERATION, ACTOR, KEY, "REVOKE", null, FINGERPRINT, null, null, "SUCCEEDED", 204, NOW));
        assertThrows(IllegalArgumentException.class, () -> new OAuthClientManagementOperation(
                OPERATION, ACTOR, KEY, "REVOKE", CLIENT, null, null, null, "SUCCEEDED", 204, NOW));
        assertThrows(IllegalArgumentException.class, () -> new OAuthClientManagementOperation(
                OPERATION, ACTOR, KEY, "REVOKE", CLIENT, FINGERPRINT, null, null, "FAILED", 204, NOW));
        assertThrows(IllegalArgumentException.class, () -> new OAuthClientManagementOperation(
                OPERATION, ACTOR, KEY, "REVOKE", CLIENT, FINGERPRINT, null, null, "SUCCEEDED", 204, null));
    }

    private static OAuthClientManagementOperation fact(String type, UUID original, UUID secret, int status) {
        return new OAuthClientManagementOperation(OPERATION, ACTOR, KEY, type, CLIENT, FINGERPRINT,
                original, secret, "SUCCEEDED", status, NOW);
    }
}
