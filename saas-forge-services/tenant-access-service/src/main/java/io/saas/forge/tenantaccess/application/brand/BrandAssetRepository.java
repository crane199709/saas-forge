package io.saas.forge.tenantaccess.application.brand;

import java.util.Optional;
import java.util.UUID;

public interface BrandAssetRepository {
    void reserveUpload(BrandAsset asset, String objectKey);
    void completeUpload(UUID tenantId, UUID assetId);
    void discardUpload(UUID tenantId, UUID assetId);
    void insert(BrandAsset asset, String objectKey, int byteSize);
    Optional<String> publicKey(UUID assetId);
    Optional<DisabledBrandAsset> disable(UUID assetId, UUID actorIdentityId);
}
