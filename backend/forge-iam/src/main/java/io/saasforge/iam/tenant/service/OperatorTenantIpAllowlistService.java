package io.saasforge.iam.tenant.service;

import io.saasforge.iam.platformrole.service.PlatformRoleService;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Operator 紧急清除 Tenant IP Allowlist；复用 forge-platform 已申报的管理租户权限。 */
@Service
public class OperatorTenantIpAllowlistService {
    private static final String MANAGE = "platform:tenant:manage";

    private final PlatformRoleService roles;
    private final TenantIpAllowlistService allowlist;
    private final AuditRecorder audits;

    OperatorTenantIpAllowlistService(
            PlatformRoleService roles, TenantIpAllowlistService allowlist, AuditRecorder audits) {
        this.roles = roles;
        this.allowlist = allowlist;
        this.audits = audits;
    }

    public void clear(UUID tenantId, UUID operatorId, String reason) {
        roles.requirePermission(operatorId, MANAGE);
        allowlist.clear(tenantId, operatorId);
        audits.record(AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.OPERATION, "tenant.ipAllowlistClear")
                .actor(operatorId)
                .resource("tenant", tenantId)
                .detail("reason", reason.strip())
                .build());
    }
}
