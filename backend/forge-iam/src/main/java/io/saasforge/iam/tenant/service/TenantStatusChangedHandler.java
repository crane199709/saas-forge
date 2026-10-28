package io.saasforge.iam.tenant.service;

import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 订阅 platform 广播的租户状态变更，更新租户副本与网关读取的 Redis 状态（ADR-0005）。 */
@Service
class TenantStatusChangedHandler implements EventHandler<TenantStatusChanged> {
    private static final Logger log = LoggerFactory.getLogger(TenantStatusChangedHandler.class);

    private final TenantStateService states;

    TenantStatusChangedHandler(TenantStateService states) {
        this.states = states;
    }

    @Override
    public String topic() {
        return TenantCreated.TOPIC;
    }

    @Override
    public Class<TenantStatusChanged> eventType() {
        return TenantStatusChanged.class;
    }

    @Override
    public void handle(TenantStatusChanged event) {
        if (!states.apply(event.tenantId(), event.name(), event.status(), event.version())) {
            log.info(
                    "Skipped stale status change of tenant {} to {} (version {})",
                    event.tenantId(),
                    event.status(),
                    event.version());
        }
    }
}
