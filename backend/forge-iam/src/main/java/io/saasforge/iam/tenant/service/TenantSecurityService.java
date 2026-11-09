package io.saasforge.iam.tenant.service;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.iam.tenant.mapper.TenantMapper;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户安全设置中的 MFA Requirement（账号安全规格 2.4）。要求由 iam 在签发进入该租户的 Token 时执行，
 * 开启后存量授权在下一次刷新时被拦，不做即时吊销（ADR-0019）。
 */
@Service
public class TenantSecurityService {
    private static final String UPDATE = "tenantSecurity.update";

    private final TenantMapper tenants;
    private final RoleService roles;
    private final AuditRecorder audits;

    TenantSecurityService(TenantMapper tenants, RoleService roles, AuditRecorder audits) {
        this.tenants = tenants;
        this.roles = roles;
        this.audits = audits;
    }

    /** 签发进入租户的 Token 前调用，不校验权限。 */
    public boolean mfaRequired(UUID tenantId) {
        return tenants.findMfaRequired(tenantId);
    }

    /** 读取当前租户的 MFA Requirement，需要 {@link IamPermissions#SECURITY_MANAGE}。 */
    public boolean mfaRequired(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.SECURITY_MANAGE);
        return tenants.findMfaRequired(actor.tenantId());
    }

    /** 设置当前租户的 MFA Requirement 并审计，需要 {@link IamPermissions#SECURITY_MANAGE}。 */
    @Transactional
    public boolean updateMfaRequired(TenantActor actor, boolean mfaRequired) {
        roles.requirePermission(actor.membershipId(), IamPermissions.SECURITY_MANAGE);
        if (tenants.updateMfaRequired(actor.tenantId(), mfaRequired, actor.identityId()) == 0) {
            // Token 中的租户在 iam 中必定已登记；未登记只可能是租户已被清理，与 Membership 失效同样按未认证处理。
            throw ForgeException.withErrorMessage(FrameworkErrorCode.AUTH_UNAUTHENTICATED, "Tenant unavailable");
        }
        audits.record(AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, UPDATE)
                .tenant(actor.tenantId())
                .actor(actor.identityId())
                .resource("tenant", actor.tenantId())
                .detail("mfaRequired", mfaRequired)
                .build());
        return mfaRequired;
    }
}
