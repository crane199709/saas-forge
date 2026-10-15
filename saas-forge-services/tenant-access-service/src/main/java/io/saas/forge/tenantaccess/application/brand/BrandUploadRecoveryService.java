package io.saas.forge.tenantaccess.application.brand;

import java.time.Clock;
import org.springframework.transaction.support.TransactionTemplate;

/** 只删除未提交素材的精确对象键，失败保留领取状态并按持久时间重试。 */
public final class BrandUploadRecoveryService {
    private final BrandUploadIntentRepository intents;
    private final BrandObjectStore objects;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public BrandUploadRecoveryService(BrandUploadIntentRepository intents, BrandObjectStore objects,
            TransactionTemplate transactions, Clock clock) {
        this.intents = intents; this.objects = objects; this.transactions = transactions; this.clock = clock;
    }

    public boolean cleanNext() {
        var claimed = transactions.execute(status -> intents.claimUpload(clock.instant()));
        if (claimed == null || claimed.isEmpty()) return false;
        var intent = claimed.orElseThrow();
        objects.delete(intent.objectKey());
        transactions.executeWithoutResult(status -> intents.completeUploadCleanup(intent));
        return true;
    }
}
