package io.saasforge.iam.referencedata.service;

import io.saasforge.iam.referencedata.mapper.DictionaryHistoryMapper;
import io.saasforge.iam.referencedata.mapper.DictionaryItemMapper;
import io.saasforge.iam.referencedata.mapper.ParameterOverrideMapper;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.provisioning.TenantDeleted;
import org.springframework.stereotype.Component;

/** 租户删除后逻辑删除其自定义字典项、字典项设置、字典变更历史与参数覆盖值；重复送达时没有可删的行，天然幂等。 */
@Component
class ReferenceDataCleaner implements TenantDataCleaner {
    private final DictionaryItemMapper items;
    private final DictionaryHistoryMapper history;
    private final ParameterOverrideMapper overrides;

    ReferenceDataCleaner(
            DictionaryItemMapper items, DictionaryHistoryMapper history, ParameterOverrideMapper overrides) {
        this.items = items;
        this.history = history;
        this.overrides = overrides;
    }

    @Override
    public void clean(TenantDeleted event) {
        items.deleteTenant(event.tenantId());
        history.deleteTenant(event.tenantId());
        overrides.deleteTenant(event.tenantId());
    }
}
