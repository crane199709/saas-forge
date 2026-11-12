package io.saasforge.platform.servicecredential.model;

import java.time.Instant;
import java.util.UUID;

public record ServiceCredentialResponse(
        UUID id, String service, String status, String fingerprint, Instant createdAt, Instant lastUsedAt) {}
