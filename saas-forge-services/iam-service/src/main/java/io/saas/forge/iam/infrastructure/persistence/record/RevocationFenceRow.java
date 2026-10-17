package io.saas.forge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class RevocationFenceRow {
    private UUID revocationRequestId;
    private String targetType;
    private UUID targetId;
    private UUID tenantId;
    private UUID membershipId;
    private UUID identityId;
    private String fenceStatus;
    private OffsetDateTime establishedAt;
    private OffsetDateTime releasedAt;

    public UUID getRevocationRequestId() { return revocationRequestId; }
    public void setRevocationRequestId(UUID revocationRequestId) { this.revocationRequestId = revocationRequestId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public UUID getTargetId() { return targetId; }
    public void setTargetId(UUID targetId) { this.targetId = targetId; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getMembershipId() { return membershipId; }
    public void setMembershipId(UUID membershipId) { this.membershipId = membershipId; }
    public UUID getIdentityId() { return identityId; }
    public void setIdentityId(UUID identityId) { this.identityId = identityId; }
    public String getFenceStatus() { return fenceStatus; }
    public void setFenceStatus(String fenceStatus) { this.fenceStatus = fenceStatus; }
    public OffsetDateTime getEstablishedAt() { return establishedAt; }
    public void setEstablishedAt(OffsetDateTime establishedAt) { this.establishedAt = establishedAt; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(OffsetDateTime releasedAt) { this.releasedAt = releasedAt; }
}
