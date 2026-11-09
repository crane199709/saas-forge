package io.saasforge.iam.tenant.service;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.iam.tenant.mapper.TenantMapper;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.ClientIpHeader;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.TenantIpAllowlist;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 当前 Tenant 的 IP 允许列表；管理权限与其他安全设置一致。 */
@Service
public class TenantIpAllowlistService {
    private final TenantMapper tenants;
    private final RoleService roles;
    private final StringRedisTemplate redis;
    private final AuditRecorder audits;

    TenantIpAllowlistService(TenantMapper tenants, RoleService roles, StringRedisTemplate redis, AuditRecorder audits) {
        this.audits = audits;
        this.redis = redis;
        this.tenants = tenants;
        this.roles = roles;
    }

    public List<String> read(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.SECURITY_MANAGE);
        return rules(actor.tenantId()).rules();
    }

    /** 供签发 Tenant Token 时读取；无权限校验，调用方须已验证 Membership。 */
    public TenantIpAllowlist rules(UUID tenantId) {
        String value = tenants.findIpAllowlist(tenantId);
        if (value == null) throw unavailable();
        return TenantIpAllowlist.decode(value);
    }

    /** 签发前校验来源，拒绝事件归于已确认的目标 Membership；无事务以免拒绝异常回滚审计。 */
    public boolean allowEntry(UUID tenantId, UUID identityId, UUID membershipId) {
        if (rules(tenantId).allows(currentIp())) return true;
        audits.record(AuditEvent.builder(AuditRealm.TENANT, AuditCategory.LOGIN, "tenant.ipAllowlistDenied")
                .tenant(tenantId)
                .actor(identityId)
                .resource("membership", membershipId)
                .failure(FrameworkErrorCode.TENANT_IP_NOT_ALLOWED.code())
                .build());
        return false;
    }

    @Transactional
    public List<String> update(TenantActor actor, List<String> rules) {
        roles.requirePermission(actor.membershipId(), IamPermissions.SECURITY_MANAGE);
        TenantIpAllowlist parsed = TenantIpAllowlist.parse(rules);
        if (!parsed.allows(currentIp())) throw new ForgeException(TenantErrorCode.CURRENT_IP_NOT_INCLUDED);
        if (tenants.updateIpAllowlist(actor.tenantId(), parsed.encode(), actor.identityId()) == 0) throw unavailable();
        // 与租户状态发布一致：UPDATE 持有租户行锁，Redis 失败则本地事务回滚。
        // 提交失败时 Redis 可能已是新值，重试或全量发布会重新以数据库为准恢复。
        redis.opsForValue().set(MembershipStateKeys.tenantIpAllowlist(actor.tenantId()), parsed.encode());
        audits.record(AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, "tenant.ipAllowlistUpdate")
                .tenant(actor.tenantId())
                .actor(actor.identityId())
                .resource("tenant", actor.tenantId())
                .detail("rules", parsed.rules())
                .build());
        return parsed.rules();
    }

    /**
     * 供 Operator 紧急清除规则；调用方负责平台权限校验和 tenant.ipAllowlistClear 审计。
     * 与租户自身更新共用行锁与同步发布，不要求当前 IP 在旧规则内。
     */
    @Transactional
    public void clear(UUID tenantId, UUID operatorId) {
        if (tenants.updateIpAllowlist(tenantId, TenantIpAllowlist.UNRESTRICTED, operatorId) == 0) {
            throw new ForgeException(TenantErrorCode.NOT_FOUND);
        }
        redis.opsForValue().set(MembershipStateKeys.tenantIpAllowlist(tenantId), TenantIpAllowlist.UNRESTRICTED);
    }

    /** 全量恢复 Redis 规则；与修改共用租户行锁，避免旧快照覆盖新规则。 */
    @Transactional
    public void publishAll() {
        for (UUID tenantId : tenants.findAllIds()) {
            String value = tenants.lockIpAllowlist(tenantId);
            if (value != null) redis.opsForValue().set(MembershipStateKeys.tenantIpAllowlist(tenantId), value);
        }
    }

    /** 新 Tenant 登记或状态先到时补齐发布；不覆盖已发布的并发修改。 */
    @Transactional
    public void publishIfAbsent(UUID tenantId) {
        String value = tenants.lockIpAllowlist(tenantId);
        if (value != null) redis.opsForValue().setIfAbsent(MembershipStateKeys.tenantIpAllowlist(tenantId), value);
    }

    /** 客户端 IP 沿用网关覆盖的可信请求头；服务直连时退回连接地址，不读取 X-Forwarded-For。 */
    public static String currentIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        var request = attributes.getRequest();
        String forwarded = request.getHeader(ClientIpHeader.NAME);
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.strip();
    }

    private static ForgeException unavailable() {
        return ForgeException.withErrorMessage(FrameworkErrorCode.AUTH_UNAUTHENTICATED, "Tenant unavailable");
    }
}
