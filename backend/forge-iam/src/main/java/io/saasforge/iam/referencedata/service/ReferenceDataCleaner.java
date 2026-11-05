package io.saasforge.iam.referencedata.service;

import io.saasforge.iam.referencedata.mapper.DictionaryItemMapper;
import io.saasforge.iam.referencedata.mapper.ParameterOverrideMapper;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.provisioning.TenantDeleted;
import org.springframework.stereotype.Component;

/** 租户删除后逻辑删除其自定义字典项、字典项设置与参数覆盖值；重复送达时没有可删的行，天然幂等。 */
@Component
class ReferenceDataCleaner implements TenantDataCleaner {
    private final DictionaryItemMapper items;
    private final ParameterOverrideMapper overrides;

    ReferenceDataCleaner(DictionaryItemMapper items, ParameterOverrideMapper overrides) {
        this.items = items;
        this.overrides = overrides;
    }

    @Override
    public void clean(TenantDeleted event) {
        items.deleteTenant(event.tenantId());
        overrides.deleteTenant(event.tenantId());
    }
}
