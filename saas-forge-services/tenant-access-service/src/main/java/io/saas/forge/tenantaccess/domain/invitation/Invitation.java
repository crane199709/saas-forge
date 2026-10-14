package io.saas.forge.tenantaccess.domain.invitation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Invitation 不持久化可恢复令牌；到期仅由固定 expiresAt 派生。 */
public record Invitation(UUID id, UUID tenantId, String email, String tokenHash,
                         Status status, Instant expiresAt, Instant createdAt, Instant updatedAt) {
    public enum Status { PENDING, ACCEPTED, REVOKED }

    public Invitation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(email);
        Objects.requireNonNull(tokenHash);
        Objects.requireNonNull(status);
        Objects.requireNonNull(expiresAt);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
    }

    public static Invitation create(UUID id, UUID tenantId, String email, String tokenHash, Instant now) {
        return new Invitation(id, tenantId, email, tokenHash, Status.PENDING,
                now.plus(Duration.ofDays(7)), now, now);
    }

    public boolean pendingAt(Instant now) {
        return status == Status.PENDING && now.isBefore(expiresAt);
    }

    public Invitation resend(String newTokenHash, Instant now) {
        if (!pendingAt(now)) throw new InvitationUnavailableException();
        return new Invitation(id, tenantId, email, newTokenHash, status, expiresAt, createdAt, now);
    }

    public Invitation revoke(Instant now) {
        if (!pendingAt(now)) throw new InvitationUnavailableException();
        return new Invitation(id, tenantId, email, tokenHash, Status.REVOKED, expiresAt, createdAt, now);
    }

    @Override
    public String toString() {
        return "Invitation[id=" + id + ", tenantId=" + tenantId + ", status=" + status + "]";
    }
}
