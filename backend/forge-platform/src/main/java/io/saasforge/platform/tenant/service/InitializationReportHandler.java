package io.saasforge.platform.tenant.service;

import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantInitializationReported;
import org.springframework.stereotype.Service;

/** 订阅各服务的初始化回报，更新平台展示的初始化状态。 */
@Service
class InitializationReportHandler implements EventHandler<TenantInitializationReported> {
    private final TenantService tenants;

    InitializationReportHandler(TenantService tenants) {
        this.tenants = tenants;
    }

    @Override
    public String topic() {
        return TenantCreated.TOPIC;
    }

    @Override
    public Class<TenantInitializationReported> eventType() {
        return TenantInitializationReported.class;
    }

    @Override
    public void handle(TenantInitializationReported event) {
        tenants.report(event.tenantId(), event.service(), event.succeeded(), event.error());
    }
}
