package io.saasforge.sdk.storage;

import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 订阅 platform 广播的 Plan 变更，按版本更新本服务中该租户的存储容量上限。降级不影响已上传的对象，只拒绝之后的上传。 */
final class StorageQuotaHandler implements EventHandler<TenantPlanChanged> {
    private static final Logger log = LoggerFactory.getLogger(StorageQuotaHandler.class);
    private static final long BYTES_PER_MIB = 1024L * 1024;

    private final StorageObjectMapper mapper;

    StorageQuotaHandler(StorageObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String topic() {
        return TenantCreated.TOPIC;
    }

    @Override
    public Class<TenantPlanChanged> eventType() {
        return TenantPlanChanged.class;
    }

    @Override
    public void handle(TenantPlanChanged event) {
        Long quotaBytes = event.storageQuotaMb() == null ? null : event.storageQuotaMb() * BYTES_PER_MIB;
        if (mapper.applyQuota(event.tenantId(), quotaBytes, event.version()) == 0) {
            log.info("Skipped stale storage quota of tenant {} (plan version {})", event.tenantId(), event.version());
        }
    }
}
