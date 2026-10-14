package io.saas.forge.tenantaccess.application.invitation;

import io.saas.forge.tenantaccess.domain.invitation.Invitation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface InvitationRepository {
    void setTarget(UUID tenantId);

    boolean lockAccessibleTenant(UUID tenantId, Instant now);

    boolean membershipExists(UUID tenantId, UUID identityId);

    boolean pendingEmailExists(UUID tenantId, String email, Instant now);

    void insert(Invitation invitation, UUID identityId, UUID actorIdentityId);

    Invitation lock(UUID tenantId, UUID invitationId);

    int generation(UUID tenantId, UUID invitationId);

    void saveResend(Invitation invitation);

    void saveRevocation(Invitation invitation);

    void markDelivery(UUID tenantId, UUID invitationId, int generation, boolean delivered, Instant now);

    List<InvitationSummary> list(UUID tenantId, Instant now);

    record InvitationSummary(UUID id, String email, String status, String deliveryStatus,
                             Instant expiresAt, Instant createdAt, Instant updatedAt) {}
}
