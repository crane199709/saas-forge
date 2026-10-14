package io.saas.forge.tenantaccess.application.invitation;

import java.util.UUID;

/** IAM 根据当前 Credential 权威状态处理首次 Password Setup，不能使用 EnsureIdentity 的历史幂等结论。 */
public interface InvitationPasswordSetupGateway {
    enum Result { MAIL_SERVICE_ACCEPTED, PASSWORD_READY, RECOVERY_REQUIRED }

    Result request(UUID requestId, UUID identityId);
}
