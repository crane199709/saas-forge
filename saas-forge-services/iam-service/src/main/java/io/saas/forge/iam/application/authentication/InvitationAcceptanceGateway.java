package io.saas.forge.iam.application.authentication;

import java.util.UUID;

public interface InvitationAcceptanceGateway {
    Result accept(String token, UUID identityId, UUID key, String traceId);
    record Result(UUID membershipId, UUID tenantId, boolean replayed) {}
}
