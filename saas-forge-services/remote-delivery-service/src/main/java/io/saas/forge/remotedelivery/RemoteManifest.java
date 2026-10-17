package io.saas.forge.remotedelivery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RemoteManifest(UUID id, String module, String version, String source, String uiVersion,
        String entrySha256, List<String> permissions, State state, UUID registeredBy, Instant registeredAt,
        UUID reviewedBy, Instant reviewedAt, UUID enabledBy, Instant enabledAt) {
    public enum State { PENDING_REVIEW, APPROVED, REJECTED, ENABLED }
}
