package io.saas.forge.tenantaccess.application.brand;

import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import org.springframework.transaction.support.TransactionTemplate;

/** 后台入口：先提交拒绝新绑定的领取事实，再删对象，最后与 Outbox 同事务提交清理事实。 */
public class BrandAssetCleanupService {
    private final BrandAssetCleanupRepository assets;
    private final BrandObjectStore objects;
    private final OutboxEventRepository outbox;
    private final BrandAssetRemovedEventFactory events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public BrandAssetCleanupService(BrandAssetCleanupRepository assets, BrandObjectStore objects,
            OutboxEventRepository outbox, BrandAssetRemovedEventFactory events,
            TransactionTemplate transactions, Clock clock) {
        this.assets = assets;
        this.objects = objects;
        this.outbox = outbox;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    public boolean cleanNext() {
        var claimed = transactions.execute(status -> assets.claim(clock.instant()));
        if (claimed == null || claimed.isEmpty()) return false;
        var asset = claimed.orElseThrow();
        // S3 DeleteObject 按精确键幂等；失败时保留 REMOVING，五分钟后可重试。
        objects.delete(asset.objectKey());
        transactions.executeWithoutResult(status -> {
            if (assets.complete(asset, clock.instant())) outbox.append(events.removed(asset));
        });
        return true;
    }
}
