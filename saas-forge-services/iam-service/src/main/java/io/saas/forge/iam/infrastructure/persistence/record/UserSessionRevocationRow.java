package io.saas.forge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class UserSessionRevocationRow {
    private UUID revocationRequestId;
    private String targetType;
    private UUID tenantId;
    private UUID membershipId;
    private UUID identityId;
    private String revocationStatus;
    private UUID cursorFamilyId;
    private long revokedFamilyCount;
    private long revokedJtiCount;
    private int attemptCount;
    private OffsetDateTime nextAttemptAt;
    private String leaseOwner;
    private OffsetDateTime leaseUntil;
    private long fencingToken;
    private OffsetDateTime recoveryExhaustedAt;
    private String lastFailure;
    private OffsetDateTime completedAt;

    public UUID getRevocationRequestId() { return revocationRequestId; }
    public void setRevocationRequestId(UUID value) { revocationRequestId = value; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String value) { targetType = value; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID value) { tenantId = value; }
    public UUID getMembershipId() { return membershipId; }
    public void setMembershipId(UUID value) { membershipId = value; }
    public UUID getIdentityId() { return identityId; }
    public void setIdentityId(UUID value) { identityId = value; }
    public String getRevocationStatus() { return revocationStatus; }
    public void setRevocationStatus(String value) { revocationStatus = value; }
    public UUID getCursorFamilyId() { return cursorFamilyId; }
    public void setCursorFamilyId(UUID value) { cursorFamilyId = value; }
    public long getRevokedFamilyCount() { return revokedFamilyCount; }
    public void setRevokedFamilyCount(long value) { revokedFamilyCount = value; }
    public long getRevokedJtiCount() { return revokedJtiCount; }
    public void setRevokedJtiCount(long value) { revokedJtiCount = value; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int value) { attemptCount = value; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(OffsetDateTime value) { nextAttemptAt = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { leaseOwner = value; }
    public OffsetDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(OffsetDateTime value) { leaseUntil = value; }
    public long getFencingToken() { return fencingToken; }
    public void setFencingToken(long value) { fencingToken = value; }
    public OffsetDateTime getRecoveryExhaustedAt() { return recoveryExhaustedAt; }
    public void setRecoveryExhaustedAt(OffsetDateTime value) { recoveryExhaustedAt = value; }
    public String getLastFailure() { return lastFailure; }
    public void setLastFailure(String value) { lastFailure = value; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime value) { completedAt = value; }
}
