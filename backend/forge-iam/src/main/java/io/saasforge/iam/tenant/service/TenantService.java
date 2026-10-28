package io.saasforge.iam.tenant.service;

import io.saasforge.iam.tenant.mapper.TenantMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** iam 中的租户目录：Tenant 主数据属于 platform，iam 保存登录与签发需要的副本。 */
@Service
public class TenantService {
    private final TenantMapper tenants;

    TenantService(TenantMapper tenants) {
        this.tenants = tenants;
    }

    /**
     * 登记新创建的租户，可重复调用；已登记时不做修改，之后的状态以 {@code TenantStatusChanged} 为准。
     *
     * @param status platform 发布 TenantCreated 时的状态
     */
    public void register(UUID tenantId, String name, String status) {
        tenants.insertIfAbsent(tenantId, name, status);
    }

    /** 租户名称，用于邀请等发给成员的通知；租户须已登记。 */
    public String name(UUID tenantId) {
        return tenants.findName(tenantId);
    }
}
