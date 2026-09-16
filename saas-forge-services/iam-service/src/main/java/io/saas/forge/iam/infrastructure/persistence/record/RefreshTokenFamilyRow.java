package io.saas.forge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class RefreshTokenFamilyRow {
    private String sessionProtocol = "LEGACY_V1";
    public String getSessionProtocol() { return sessionProtocol; }
    public void setSessionProtocol(String value) { sessionProtocol = value; }
    private UUID id;
    private UUID identityId;
    private String familyPurpose;
    private UUID initialCredentialId;
    private UUID membershipId;
    private UUID tenantId;
    private long contextVersion;
    private OffsetDateTime lastUsedAt;
    private OffsetDateTime absoluteExpiresAt;
    private OffsetDateTime revokedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getIdentityId() { return identityId; }
    public void setIdentityId(UUID identityId) { this.identityId = identityId; }
    public String getFamilyPurpose() { return familyPurpose; }
    public void setFamilyPurpose(String familyPurpose) { this.familyPurpose = familyPurpose; }
    public UUID getInitialCredentialId() { return initialCredentialId; }
    public void setInitialCredentialId(UUID initialCredentialId) { this.initialCredentialId = initialCredentialId; }
    public UUID getMembershipId() { return membershipId; }
    public void setMembershipId(UUID membershipId) { this.membershipId = membershipId; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public long getContextVersion() { return contextVersion; }
    public void setContextVersion(long contextVersion) { this.contextVersion = contextVersion; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public OffsetDateTime getAbsoluteExpiresAt() { return absoluteExpiresAt; }
    public void setAbsoluteExpiresAt(OffsetDateTime absoluteExpiresAt) { this.absoluteExpiresAt = absoluteExpiresAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
}
