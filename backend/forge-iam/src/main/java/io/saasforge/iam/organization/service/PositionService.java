package io.saasforge.iam.organization.service;

import io.saasforge.iam.organization.mapper.AssignmentMapper;
import io.saasforge.iam.organization.mapper.DepartmentMapper;
import io.saasforge.iam.organization.mapper.PositionMapper;
import io.saasforge.iam.organization.model.PositionRow;
import io.saasforge.iam.organization.model.TenantActor;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
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

    PositionService(
            PositionMapper positions,
            DepartmentMapper departments,
            AssignmentMapper assignments,
            RoleService roles,
            TransactionTemplate transactions) {
        this.positions = positions;
        this.departments = departments;
        this.assignments = assignments;
        this.roles = roles;
        this.transactions = transactions;
    }

    public List<PositionRow> list(TenantActor actor) {
        roles.requireTenantAdmin(actor.membershipId());
        return positions.list(actor.tenantId());
    }

    public PositionRow create(TenantActor actor, String name) {
        roles.requireTenantAdmin(actor.membershipId());
        UUID id = UuidV7.generate();
        positions.insert(actor.tenantId(), id, name, actor.identityId());
        return new PositionRow(id, name);
    }

    public PositionRow rename(TenantActor actor, UUID id, String name) {
        roles.requireTenantAdmin(actor.membershipId());
        if (positions.rename(actor.tenantId(), id, name, actor.identityId()) == 0) {
            throw new ForgeException(OrganizationErrorCode.POSITION_NOT_FOUND);
        }
        return new PositionRow(id, name);
    }

    /** 仍有成员担任时拒绝删除；与成员分配在同一把租户锁下判断，避免删除的同时被分配。 */
    public void delete(TenantActor actor, UUID id) {
        roles.requireTenantAdmin(actor.membershipId());
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
