package io.saas.forge.iam.application.authorization;

import java.util.UUID;

public record PlatformAdminGrantResult(UUID identityId, UUID roleAssignmentId,
        boolean newlyGranted, SetupState setupState) {
    public enum SetupState { PENDING, DELIVERED, PASSWORD_READY, RECOVERY_REQUIRED, ALREADY_GRANTED }
}
