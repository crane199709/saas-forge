package io.saas.forge.tenantaccess.application.brand;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

public class BrandAssetService {
    private final PermissionAuthority authority;
    private final BrandAssetRepository assets;
    private final BrandObjectStore objects;
    private final BrandAssetEventFactory events;
    private final OutboxEventRepository outbox;
    private final UuidV7Generator ids;
    private final TransactionTemplate transactions;

    public BrandAssetService(PermissionAuthority authority, BrandAssetRepository assets, BrandObjectStore objects,
            BrandAssetEventFactory events, OutboxEventRepository outbox, UuidV7Generator ids,
            TransactionTemplate transactions) {
        this.authority = authority;
        this.assets = assets;
        this.objects = objects;
        this.events = events;
        this.outbox = outbox;
        this.ids = ids;
        this.transactions = transactions;
    }

    public BrandAsset upload(TenantContextSnapshot context, BrandPng.Kind kind, byte[] png) {
        if (!authority.allowed(context, "system:brand:manage")) throw new Forbidden();
        var dimensions = BrandPng.validate(png, kind);
        UUID id = ids.next();
        String key = context.tenantId() + "/" + id + ".png";
        var asset = new BrandAsset(id, context.tenantId(), kind, "/brands/" + id,
                dimensions.width(), dimensions.height());
        transactions.executeWithoutResult(status -> assets.reserveUpload(asset, key));
        try {
            objects.put(key, png);
        } catch (RuntimeException failure) {
            cleanupFailedUpload(asset, key, failure);
            throw failure;
        }
        try {
            transactions.executeWithoutResult(status -> {
                try {
                    assets.completeUpload(asset.tenantId(), asset.id());
                    assets.insert(asset, key, png.length);
                    outbox.append(events.uploaded(context, asset));
                } catch (RuntimeException failure) {
                    throw new LocalWriteFailure(failure);
                }
            });
        } catch (LocalWriteFailure knownRollback) {
            RuntimeException failure = knownRollback.original;
            cleanupFailedUpload(asset, key, failure);
            throw failure;
        }
        return asset;
    }

    private void cleanupFailedUpload(BrandAsset asset, String key, RuntimeException failure) {
        // 删除失败时保留先前提交的登记，由 worker 按精确键重试。
        try {
            objects.delete(key);
            transactions.executeWithoutResult(status -> assets.discardUpload(asset.tenantId(), asset.id()));
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static final class LocalWriteFailure extends RuntimeException {
        private final RuntimeException original;
        LocalWriteFailure(RuntimeException original) { super(original); this.original = original; }
    }

    public byte[] publicRead(UUID id) {
        String key = assets.publicKey(id).orElseThrow(Missing::new);
        return objects.get(key);
    }

    public static class Forbidden extends RuntimeException {}
    public static class Missing extends RuntimeException {}
}
