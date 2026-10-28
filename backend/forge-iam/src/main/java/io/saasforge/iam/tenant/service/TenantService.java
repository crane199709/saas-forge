package io.saasforge.iam.tenant.service;

import io.saasforge.iam.tenant.mapper.TenantMapper;
import io.saasforge.sdk.core.MembershipStateKeys;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** iam 中的租户目录：Tenant 主数据属于 platform，iam 保存登录与签发需要的副本。 */
@Service
public class TenantService {
    private final TenantMapper tenants;

    TenantService(TenantMapper tenants) {
        this.tenants = tenants;
    }

    // TODO(#15): 生命周期接入后同步 platform 的初始状态；在此之前新租户与 platform 一样直接为 active。
    /** 登记新创建的租户，可重复调用。 */
    public void register(UUID tenantId, String name) {
        tenants.insertIfAbsent(tenantId, name, MembershipStateKeys.ACTIVE);
    }
}
