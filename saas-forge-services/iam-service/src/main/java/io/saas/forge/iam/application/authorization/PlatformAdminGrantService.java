package io.saas.forge.iam.application.authorization;

import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryResult;
import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryService;
import io.saas.forge.iam.application.authentication.IdentityCredentialRecoveryRequiredException;
import java.util.UUID;

/** 提交角色事实后才尝试首次 Setup；同一请求重试继续持久投递工作项。 */
public class PlatformAdminGrantService {
    private final PlatformAdminGrantTransaction transaction;
    private final PasswordSetupDeliveryService deliveries;

    public PlatformAdminGrantService(PlatformAdminGrantTransaction transaction,
            PasswordSetupDeliveryService deliveries) {
        this.transaction = transaction;
        this.deliveries = deliveries;
    }

    public PlatformAdminGrantResult grant(UUID actor, UUID requestId, String email, String traceId) {
        PlatformAdminGrant grant = transaction.grant(actor, requestId, email, traceId);
        if (!grant.newlyGranted()) {
            return new PlatformAdminGrantResult(grant.identityId(), grant.roleAssignmentId(), false,
                    PlatformAdminGrantResult.SetupState.ALREADY_GRANTED);
        }
        PlatformAdminGrantResult.SetupState setup = switch (grant.credentialStatus()) {
            case PASSWORD_READY -> PlatformAdminGrantResult.SetupState.PASSWORD_READY;
            case RECOVERY_REQUIRED -> PlatformAdminGrantResult.SetupState.RECOVERY_REQUIRED;
            case SETUP_ALLOWED -> {
                if (grant.completedSetupState() != null) yield grant.completedSetupState();
                yield transaction.claimDelivery(requestId)
                        ? deliver(requestId, grant.identityId(), grant.setupCallerClientId(), traceId)
                        : PlatformAdminGrantResult.SetupState.PENDING;
            }
        };
        return new PlatformAdminGrantResult(
                grant.identityId(), grant.roleAssignmentId(), grant.newlyGranted(), setup);
    }

    /** 定时恢复授权事务已提交、邮件发送未完成的工作项。 */
    public void recoverPending() {
        for (var work : transaction.pendingDeliveries()) {
            if (!transaction.claimDelivery(work.requestId())) continue;
            try {
                deliver(work.requestId(), work.identityId(), work.callerClientId(), null);
            } catch (RuntimeException ignored) {
                // 租约到期后重试；角色事实与待投递请求均已持久化。
            }
        }
    }

    private PlatformAdminGrantResult.SetupState deliver(UUID requestId, UUID identityId,
            UUID callerClientId, String traceId) {
        PlatformAdminGrantResult.SetupState state;
        try {
            PasswordSetupDeliveryResult result = deliveries.deliver(callerClientId, requestId, identityId, traceId);
            state = result == PasswordSetupDeliveryResult.DELIVERED
                    ? PlatformAdminGrantResult.SetupState.DELIVERED
                    : PlatformAdminGrantResult.SetupState.PASSWORD_READY;
        } catch (IdentityCredentialRecoveryRequiredException recovery) {
            state = PlatformAdminGrantResult.SetupState.RECOVERY_REQUIRED;
        }
        transaction.completeDelivery(requestId, state);
        return state;
    }
}
