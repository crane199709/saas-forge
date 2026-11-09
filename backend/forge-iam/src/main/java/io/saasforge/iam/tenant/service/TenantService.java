package io.saasforge.iam.tenant.service;

import io.saasforge.iam.tenant.mapper.TenantMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** iam 中的租户目录：Tenant 主数据属于 platform，iam 保存登录与签发需要的副本。 */
@Service
public class TenantService {
    private final TenantMapper tenants;
    private final TenantIpAllowlistService allowlists;

    TenantService(TenantMapper tenants, TenantIpAllowlistService allowlists) {
        this.allowlists = allowlists;
        this.tenants = tenants;
    }

    /**
     * 登记新创建的租户，可重复调用；已登记时不做修改，之后的状态以 {@code TenantStatusChanged} 为准。
     *
     * @param status platform 发布 TenantCreated 时的状态
     */
    @Transactional
    public void register(UUID tenantId, String name, String status) {
        tenants.insertIfAbsent(tenantId, name, status);
        allowlists.publishIfAbsent(tenantId);
    }

    /** 按状态列出未删除的租户 ID，供受信任服务发现任务目标。 */
    public List<UUID> idsByStatus(List<String> statuses, long offset, int limit) {
        return tenants.findIdsByStatus(statuses, offset, limit);
    }

    /** 未删除且匹配状态的租户总数，用于内部租户目录分页。 */
    public long countByStatus(List<String> statuses) {
        return tenants.countByStatus(statuses);
    }

    /** 租户名称，用于邀请等发给成员的通知；租户须已登记。 */
    public String name(UUID tenantId) {
        return tenants.findName(tenantId);
    }
}
