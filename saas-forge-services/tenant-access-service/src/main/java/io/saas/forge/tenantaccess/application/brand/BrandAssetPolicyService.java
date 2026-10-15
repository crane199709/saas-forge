package io.saas.forge.tenantaccess.application.brand;

import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class BrandAssetPolicyService {
    private final BrandAssetRepository assets;
    private final OutboxEventRepository outbox;
    private final BrandAssetDisabledEventFactory events;

    public BrandAssetPolicyService(BrandAssetRepository assets, OutboxEventRepository outbox,
            BrandAssetDisabledEventFactory events) {
        this.assets = assets;
        this.outbox = outbox;
        this.events = events;
    }

    @Transactional
    public DisabledBrandAsset disable(UUID actorIdentityId, UUID assetId) {
        DisabledBrandAsset asset = assets.disable(assetId, actorIdentityId).orElseThrow(BrandAssetService.Missing::new);
        if (asset.changed()) outbox.append(events.disabled(actorIdentityId, asset));
        return asset;
    }
}
