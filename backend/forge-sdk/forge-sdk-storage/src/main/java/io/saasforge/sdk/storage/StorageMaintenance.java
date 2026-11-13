package io.saasforge.sdk.storage;

import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.scheduling.TenantTask;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 在业务服务内按当前 Tenant 清理无引用文件；不跨服务访问对象或数据库。 */
public final class StorageMaintenance {
    private static final Logger log = LoggerFactory.getLogger(StorageMaintenance.class);
    private final ObjectStorage storage;
    private final StorageObjectMapper mapper;
    private final FileReferenceChecks references;
    private final Clock clock;

    StorageMaintenance(ObjectStorage storage, StorageObjectMapper mapper, FileReferenceChecks references, Clock clock) {
        this.storage = storage;
        this.mapper = mapper;
        this.references = references;
        this.clock = clock;
    }

    /**
     * 先检查引用并失效到期文件，再独立检查引用并删除超过七天宽限期的文件。
     * 缺检查器或检查失败保留该批；游标继续推进，一个用途的失败不阻止其他用途。
     */
    @TenantTask(name = "forge-storage-retention", fixedDelay = "1h")
    public void clean() {
        CurrentTenant.require();
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (String purpose : mapper.retentionPurposes()) {
            for (String phase : List.of("expire", "purge")) {
                try {
                    cleanPurpose(purpose, phase, now);
                } catch (RuntimeException error) {
                    warn(purpose, phase);
                }
            }
        }
    }

    private void cleanPurpose(String purpose, String phase, OffsetDateTime now) {
        UUID after = null;
        while (true) {
            var candidates = phase.equals("expire")
                    ? mapper.expiryCandidates(purpose, now, after, references.batchSize())
                    : mapper.purgeCandidates(
                            purpose,
                            now,
                            now.minus(StorageObjectLifecycle.PURGE_GRACE_PERIOD),
                            after,
                            references.batchSize());
            if (candidates.isEmpty()) {
                return;
            }
            after = candidates.get(candidates.size() - 1).id();
            var allowed = references.unreferenced(
                    purpose, candidates.stream().map(StorageObjectRecord::id).toList(), phase);
            for (UUID id : allowed) {
                try {
                    if (phase.equals("expire")) {
                        storage.expire(id);
                    } else {
                        storage.purge(id, purpose);
                    }
                } catch (RuntimeException error) {
                    warn(purpose, phase);
                }
            }
        }
    }

    private static void warn(String purpose, String phase) {
        log.warn(
                "Storage retention skipped tenant={} purpose={} phase={} reason=operation-failure",
                CurrentTenant.require(),
                purpose,
                phase);
    }
}
