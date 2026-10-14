package io.saas.forge.iam.application.authorization;

import io.saas.forge.iam.application.authentication.RevocationIndex;
import io.saas.forge.iam.application.authentication.RevocationIndexUnavailableException;
import java.time.Instant;
import java.time.Clock;
import java.util.UUID;

/** 成功响应只在已提交的平台撤权 jti 被同步索引后返回；失败由持久工作项恢复。 */
public class PlatformAdminRevokeService {
    private final PlatformAdminRevokeTransaction transaction;
    private final RevocationIndex index;
    private final Clock clock;

    public PlatformAdminRevokeService(PlatformAdminRevokeTransaction transaction, RevocationIndex index, Clock clock) {
        this.transaction = transaction;
        this.index = index;
        this.clock = clock;
    }

    public void revoke(UUID actor, UUID target, UUID requestId, String traceId) {
        if (transaction.prepare(actor, target, requestId, traceId)) {
            if (!index.isReady()) throw new RevocationIndexUnavailableException();
            return;
        }
        deliver(requestId);
    }

    public void recoverPending() {
        for (UUID requestId : transaction.pendingRequests()) {
            try { deliver(requestId); }
            catch (RuntimeException ignored) {
                // 已提交的角色与 Token 撤销事实保持有效，定时任务下次重试。
            }
        }
    }

    private void deliver(UUID requestId) {
        Instant now = clock.instant();
        for (var issuance : transaction.pendingJtis(requestId))
            index.revokeJti(issuance.jti(), issuance.expiresAt(), now);
        if (!index.isReady()) throw new RevocationIndexUnavailableException();
        transaction.complete(requestId);
    }
}
