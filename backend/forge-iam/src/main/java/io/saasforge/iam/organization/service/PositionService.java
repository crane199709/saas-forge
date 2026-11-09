package io.saasforge.iam.organization.service;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.organization.mapper.AssignmentMapper;
import io.saasforge.iam.organization.mapper.DepartmentMapper;
import io.saasforge.iam.organization.mapper.PositionMapper;
import io.saasforge.iam.organization.model.PositionRow;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 租户的 Position。主体属性按岗位标识引用，改名不影响已分配成员。 */
@Service
public class PositionService {
    private final PositionMapper positions;
    private final DepartmentMapper departments;
    private final AssignmentMapper assignments;
    private final RoleService roles;
    private final TransactionTemplate transactions;
    private final AuditRecorder audits;

    PositionService(
            PositionMapper positions,
            DepartmentMapper departments,
            AssignmentMapper assignments,
            RoleService roles,
            TransactionTemplate transactions,
            AuditRecorder audits) {
        this.positions = positions;
        this.departments = departments;
        this.assignments = assignments;
        this.roles = roles;
        this.transactions = transactions;
        this.audits = audits;
    }

    public List<PositionRow> list(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        return positions.list(actor.tenantId());
    }

    /** 指定删除状态的岗位列表。 */
    public List<PositionRow> list(TenantActor actor, boolean deleted) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        return deleted ? positions.listDeleted(actor.tenantId()) : positions.list(actor.tenantId());
    }

    /** 与岗位删除和成员分配串行恢复；岗位名称允许重复。 */
    public void restore(TenantActor actor, UUID id) {
        AuditEvent.Builder event = AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, "position.restore")
                .tenant(actor.tenantId())
                .actor(actor.identityId())
                .resource("position", id);
        try {
            roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
            transactions.executeWithoutResult(status -> {
                departments.lockTenant(actor.tenantId());
                if (positions.restore(actor.tenantId(), id, actor.identityId()) == 0) {
                    throw new ForgeException(OrganizationErrorCode.POSITION_NOT_FOUND);
                }
            });
        } catch (RuntimeException failure) {
            audits.record(event.failure(
                            failure instanceof ForgeException forge
                                    ? forge.errorCode().code()
                                    : FrameworkErrorCode.SYSTEM_ERROR.code())
                    .build());
            throw failure;
        }
        audits.record(event.build());
    }

    /** 全部岗位，不校验权限；供角色编辑中的条件编辑器选择岗位。 */
    public List<PositionRow> all(UUID tenantId) {
        return positions.list(tenantId);
    }

    public PositionRow create(TenantActor actor, String name) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        UUID id = UuidV7.generate();
        positions.insert(actor.tenantId(), id, name, actor.identityId());
        return positions.find(actor.tenantId(), id);
    }

    public PositionRow rename(TenantActor actor, UUID id, String name) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        if (positions.rename(actor.tenantId(), id, name, actor.identityId()) == 0) {
            throw new ForgeException(OrganizationErrorCode.POSITION_NOT_FOUND);
        }
        return positions.find(actor.tenantId(), id);
    }

    /** 仍有成员担任时拒绝删除；与成员分配在同一把租户锁下判断，避免删除的同时被分配。 */
    public void delete(TenantActor actor, UUID id) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        transactions.executeWithoutResult(status -> {
            departments.lockTenant(actor.tenantId());
            if (assignments.countByPosition(actor.tenantId(), id) > 0) {
                throw new ForgeException(OrganizationErrorCode.POSITION_IN_USE);
            }
            if (positions.delete(actor.tenantId(), id, actor.identityId()) == 0) {
                throw new ForgeException(OrganizationErrorCode.POSITION_NOT_FOUND);
            }
        });
    }
}
