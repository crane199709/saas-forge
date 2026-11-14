package io.saasforge.platform.tenant.service;

import io.saasforge.platform.tenant.mapper.TenantAiUsageMapper;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.provisioning.TenantDeleted;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 运营投影也随租户最终删除；全局表必须显式限制租户，不能依赖 SQL 租户拦截。 */
@Service
public class TenantAiUsageCleaner implements TenantDataCleaner {
    private final TenantAiUsageMapper mapper;

    public TenantAiUsageCleaner(TenantAiUsageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void clean(TenantDeleted event) {
        if (!CurrentTenant.require().equals(event.tenantId())) {
            throw new IllegalStateException("TenantDeleted envelope mismatch");
        }
        mapper.clean(event.tenantId());
    }
}
