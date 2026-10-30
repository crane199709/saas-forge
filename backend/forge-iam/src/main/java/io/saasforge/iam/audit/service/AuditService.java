package io.saasforge.iam.audit.service;

import io.saasforge.iam.audit.mapper.AuditMapper;
import io.saasforge.iam.audit.model.AuditQuery;
import io.saasforge.iam.audit.model.AuditRecord;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.permission.service.PlatformPermissions;
import io.saasforge.iam.platformrole.service.PlatformRoleService;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * 写入与查询审计表。Tenant Admin 只能查到本租户的 Tenant Realm 事件；持有 {@value PlatformPermissions#AUDIT_READ} 的 Operator
 * 查到 Platform Realm 事件，两者互不可见。
 */
@Service
public class AuditService {
    private static final String TENANT = "tenant";
    private static final String PLATFORM = "platform";
    // 消息体是跨服务契约，与 SDK 的序列化一致，不跟随服务对 JsonMapper 的定制
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuditMapper audits;
    private final RoleService roles;
    private final PlatformRoleService platformRoles;
    // 已确认存在的分区，避免每条事件都调用建分区函数；进程重启后重新确认一次
    private final Set<LocalDate> partitions = ConcurrentHashMap.newKeySet();

    AuditService(AuditMapper audits, RoleService roles, PlatformRoleService platformRoles) {
        this.audits = audits;
        this.roles = roles;
        this.platformRoles = platformRoles;
    }

    /** 写入一条事件；所在月份的分区不存在时先创建。同一事件重复写入时忽略。 */
    public void store(AuditEvent event) {
        LocalDate month =
                event.occurredAt().atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        if (!partitions.contains(month)) {
            audits.ensurePartition(month);
            // 分区随所在事务提交才真正存在；事务回滚时不能记为已存在，否则之后该月的写入都会失败
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        partitions.add(month);
                    }
                });
            } else {
                partitions.add(month);
            }
        }
        audits.insert(
                event.id(),
                event.occurredAt().atOffset(ZoneOffset.UTC),
                lower(event.realm()),
                event.realm() == AuditRealm.TENANT ? event.tenantId() : null,
                event.actorId(),
                lower(event.category()),
                event.action(),
                event.resourceType(),
                event.resourceId(),
                lower(event.outcome()),
                event.errorCode(),
                JSON.writeValueAsString(event.detail()),
                event.service(),
                event.traceId());
    }

    /** 本租户的一页审计记录，按发生时间倒序；要求 {@value IamPermissions#AUDIT_READ}。 */
    public List<AuditRecord> searchTenant(TenantActor actor, AuditQuery query, long offset, int limit) {
        roles.requirePermission(actor.membershipId(), IamPermissions.AUDIT_READ);
        return audits.search(TENANT, actor.tenantId(), query, offset, limit);
    }

    public long countTenant(TenantActor actor, AuditQuery query) {
        roles.requirePermission(actor.membershipId(), IamPermissions.AUDIT_READ);
        return audits.count(TENANT, actor.tenantId(), query);
    }

    /** Platform Realm 的一页审计记录，按发生时间倒序；要求 {@value PlatformPermissions#AUDIT_READ}。 */
    public List<AuditRecord> searchPlatform(UUID operatorId, AuditQuery query, long offset, int limit) {
        platformRoles.requirePermission(operatorId, PlatformPermissions.AUDIT_READ);
        return audits.search(PLATFORM, null, query, offset, limit);
    }

    public long countPlatform(UUID operatorId, AuditQuery query) {
        platformRoles.requirePermission(operatorId, PlatformPermissions.AUDIT_READ);
        return audits.count(PLATFORM, null, query);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
