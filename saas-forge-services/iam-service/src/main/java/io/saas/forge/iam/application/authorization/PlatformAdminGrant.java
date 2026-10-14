package io.saas.forge.iam.application.authorization;

import io.saas.forge.iam.domain.identity.IdentityCredentialStatus;
import java.util.UUID;

public record PlatformAdminGrant(UUID identityId, UUID roleAssignmentId,
        IdentityCredentialStatus credentialStatus, boolean newlyGranted,
        UUID setupCallerClientId, PlatformAdminGrantResult.SetupState completedSetupState) {
}
