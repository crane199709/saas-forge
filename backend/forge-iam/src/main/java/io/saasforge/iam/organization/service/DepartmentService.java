package io.saasforge.iam.organization.service;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.organization.mapper.AssignmentMapper;
import io.saasforge.iam.organization.mapper.DepartmentMapper;
import io.saasforge.iam.organization.model.DepartmentRow;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.core.api.ResourceErrorCode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 租户的 Department 树，层级以 ltree 路径存储（ADR-0006）。 */
@Service
public class DepartmentService {
    private final DepartmentMapper departments;
    private final AssignmentMapper assignments;
    private final RoleService roles;
    private final SubjectChangePublisher subjects;
    private final TransactionTemplate transactions;
    private final AuditRecorder audits;

    DepartmentService(
            DepartmentMapper departments,
            AssignmentMapper assignments,
            RoleService roles,
            SubjectChangePublisher subjects,
            TransactionTemplate transactions,
            AuditRecorder audits) {
        this.departments = departments;
        this.assignments = assignments;
        this.roles = roles;
        this.subjects = subjects;
        this.transactions = transactions;
        this.audits = audits;
    }

    /** 全部部门，按路径排序，上级总在下级之前。 */
    public List<DepartmentRow> list(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        return departments.list(actor.tenantId());
    }

    /** 指定删除状态的部门列表；常规组织选择仍只返回未删除部门。 */
    public List<DepartmentRow> list(TenantActor actor, boolean deleted) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        return deleted ? departments.listDeleted(actor.tenantId()) : departments.list(actor.tenantId());
    }

    /** 恢复时按当前上级重算路径，与部门删除、移动使用同一把锁。 */
    public void restore(TenantActor actor, UUID id) {
        AuditEvent.Builder event = AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, "department.restore")
                .tenant(actor.tenantId())
                .actor(actor.identityId())
                .resource("department", id);
        try {
            roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
            transactions.executeWithoutResult(status -> {
                departments.lockTenant(actor.tenantId());
                DepartmentRow deleted = departments.findDeleted(actor.tenantId(), id);
                if (deleted == null) throw new ForgeException(OrganizationErrorCode.DEPARTMENT_NOT_FOUND);
                DepartmentRow parent =
                        deleted.parentId() == null ? null : departments.find(actor.tenantId(), deleted.parentId());
                if (deleted.parentId() != null && parent == null) {
                    throw new ForgeException(ResourceErrorCode.RESTORE_BLOCKED);
                }
                if (departments.restore(actor.tenantId(), id, childPath(parent, id), actor.identityId()) == 0) {
                    throw new ForgeException(OrganizationErrorCode.DEPARTMENT_NOT_FOUND);
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

    /** 全部部门，按路径排序，不校验权限；供角色编辑中的条件编辑器选择部门。 */
    public List<DepartmentRow> all(UUID tenantId) {
        return departments.list(tenantId);
    }

    /** 在上级之下新建部门；{@code parentId} 为 null 时为根部门。 */
    public DepartmentRow create(TenantActor actor, String name, UUID parentId) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        return transactions.execute(status -> {
            departments.lockTenant(actor.tenantId());
            UUID id = UuidV7.generate();
            String path = childPath(parentId == null ? null : require(actor, parentId), id);
            departments.insert(actor.tenantId(), id, parentId, name, path, actor.identityId());
            return departments.find(actor.tenantId(), id);
        });
    }

    /** 改名不改变路径，成员的主体属性不受影响。 */
    public DepartmentRow rename(TenantActor actor, UUID id, String name) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        if (departments.rename(actor.tenantId(), id, name, actor.identityId()) == 0) {
            throw new ForgeException(OrganizationErrorCode.DEPARTMENT_NOT_FOUND);
        }
        return departments.find(actor.tenantId(), id);
    }

    /**
     * 把部门连同子树移到新的上级之下，{@code parentId} 为 null 时移为根部门。子树中成员的部门路径随之改变，
     * 提交后递增他们的 version。
     */
    public DepartmentRow move(TenantActor actor, UUID id, UUID parentId) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        Moved moved = transactions.execute(status -> {
            departments.lockTenant(actor.tenantId());
            DepartmentRow department = require(actor, id);
            if (Objects.equals(department.parentId(), parentId)) return new Moved(department, List.of());
            DepartmentRow parent = parentId == null ? null : require(actor, parentId);
            if (parent != null && under(parent.path(), department.path())) {
                throw new ForgeException(OrganizationErrorCode.INVALID_DEPARTMENT_MOVE);
            }
            String path = childPath(parent, id);
            departments.move(actor.tenantId(), id, parentId, department.path(), path, actor.identityId());
            return new Moved(
                    departments.find(actor.tenantId(), id), assignments.membershipsUnder(actor.tenantId(), path));
        });
        subjects.changed(moved.affected());
        return moved.department();
    }

    /** 有未删除的子部门或成员时拒绝删除，不级联。 */
    public void delete(TenantActor actor, UUID id) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        transactions.executeWithoutResult(status -> {
            departments.lockTenant(actor.tenantId());
            require(actor, id);
            if (departments.countChildren(actor.tenantId(), id) > 0
                    || assignments.countByDepartment(actor.tenantId(), id) > 0) {
                throw new ForgeException(OrganizationErrorCode.DEPARTMENT_NOT_EMPTY);
            }
            departments.delete(actor.tenantId(), id, actor.identityId());
        });
    }

    private DepartmentRow require(TenantActor actor, UUID id) {
        DepartmentRow department = departments.find(actor.tenantId(), id);
        if (department == null) throw new ForgeException(OrganizationErrorCode.DEPARTMENT_NOT_FOUND);
        return department;
    }

    // ltree 标签用去掉连字符的 id：只含字母数字，在不允许连字符的旧版 PostgreSQL 上也合法。
    private static String childPath(DepartmentRow parent, UUID id) {
        String label = id.toString().replace("-", "");
        return parent == null ? label : parent.path() + "." + label;
    }

    private static boolean under(String path, String root) {
        return path.equals(root) || path.startsWith(root + ".");
    }

    private record Moved(DepartmentRow department, List<UUID> affected) {}
}
