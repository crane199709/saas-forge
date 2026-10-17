package io.saas.forge.tenantaccess.application.brand;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BrandUploadIntentRepository {
    Optional<Intent> claimUpload(Instant now);
    void completeUploadCleanup(Intent intent);

    record Intent(UUID assetId, UUID tenantId, String objectKey) {}
}
