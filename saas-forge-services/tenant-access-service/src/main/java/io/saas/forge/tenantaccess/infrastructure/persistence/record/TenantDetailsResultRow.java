package io.saas.forge.tenantaccess.infrastructure.persistence.record;

import java.time.OffsetDateTime;

public record TenantDetailsResultRow(
        String requestFingerprint,
        String displayName,
        String status,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
