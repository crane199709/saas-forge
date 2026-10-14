package io.saas.forge.tenantaccess.application.invitation;

import io.saas.forge.tenantaccess.domain.invitation.Invitation;
import java.time.Instant;
import java.util.UUID;

public interface InvitationAcceptanceRepository {
    Target locate(String tokenHash);
    void setTarget(UUID tenantId);
    boolean lockAccessibleTenant(UUID tenantId, Instant now);
    Invitation lockInvitation(UUID tenantId, UUID invitationId);
    UUID invitedIdentity(UUID tenantId, UUID invitationId);
    boolean membershipExists(UUID tenantId, UUID identityId);
    Attempt lockAttempt(UUID tenantId, UUID invitationId);
    void saveAttempt(Attempt attempt, Instant now);
    void state(Attempt attempt, String state, Instant now);
    void activate(Attempt attempt, Instant now);

    record Target(UUID invitationId, UUID tenantId) {}
    record Attempt(UUID invitationId, UUID tenantId, UUID identityId, UUID key,
                   UUID consumeId, UUID releaseId, UUID membershipId, String state) {
        public Attempt withState(String value) {
            return new Attempt(invitationId, tenantId, identityId, key, consumeId, releaseId, membershipId, value);
        }
    }
}
