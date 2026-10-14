package io.saas.forge.tenantaccess.application.brand;

import java.time.Instant;
import java.util.Optional;

public interface BrandAssetCleanupRepository {
    Optional<ClaimedBrandAsset> claim(Instant now);

    boolean complete(ClaimedBrandAsset asset, Instant now);
}
