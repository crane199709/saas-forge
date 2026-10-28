package io.saasforge.iam.organization.service;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.organization.mapper.AssignmentMapper;
import io.saasforge.iam.organization.mapper.DepartmentMapper;
import io.saasforge.iam.organization.model.DepartmentRow;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
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

    DepartmentService(
            DepartmentMapper departments,
            AssignmentMapper assignments,
            RoleService roles,
            SubjectChangePublisher subjects,
            TransactionTemplate transactions) {
        this.departments = departments;
        this.assignments = assignments;
        this.roles = roles;
        this.subjects = subjects;
        this.transactions = transactions;
    }

    /** 全部部门，按路径排序，上级总在下级之前。 */
    public List<DepartmentRow> list(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        return departments.list(actor.tenantId());
    }

    /** 在上级之下新建部门；{@code parentId} 为 null 时为根部门。 */
    public DepartmentRow create(TenantActor actor, String name, UUID parentId) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        return transactions.execute(status -> {
            departments.lockTenant(actor.tenantId());
            UUID id = UuidV7.generate();
            String path = childPath(parentId == null ? null : require(actor, parentId), id);
            departments.insert(actor.tenantId(), id, parentId, name, path, actor.identityId());
            return new DepartmentRow(id, parentId, name, path);
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
                    new DepartmentRow(id, parentId, department.name(), path),
                    assignments.membershipsUnder(actor.tenantId(), path));
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
