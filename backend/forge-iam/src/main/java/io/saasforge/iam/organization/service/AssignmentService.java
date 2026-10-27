package io.saasforge.iam.organization.service;

import io.saasforge.iam.membership.model.Member;
import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.organization.mapper.AssignmentMapper;
import io.saasforge.iam.organization.mapper.DepartmentMapper;
import io.saasforge.iam.organization.mapper.PositionMapper;
import io.saasforge.iam.organization.model.DepartmentAssignmentRow;
import io.saasforge.iam.organization.model.MemberAssignment;
import io.saasforge.iam.organization.model.PositionAssignmentRow;
import io.saasforge.iam.organization.model.TenantActor;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Membership 的主部门、兼任部门、岗位与扩展属性值。分配变化后递增成员 version，主体属性随之重新发布。 */
@Service
public class AssignmentService {
    private final MembershipService memberships;
    private final AssignmentMapper assignments;
    private final DepartmentMapper departments;
    private final PositionMapper positions;
    private final ExtendedAttributeService attributes;
    private final RoleService roles;
    private final SubjectChangePublisher subjects;
    private final TransactionTemplate transactions;

    AssignmentService(
            MembershipService memberships,
            AssignmentMapper assignments,
            DepartmentMapper departments,
            PositionMapper positions,
            ExtendedAttributeService attributes,
            RoleService roles,
            SubjectChangePublisher subjects,
            TransactionTemplate transactions) {
        this.memberships = memberships;
        this.assignments = assignments;
        this.departments = departments;
        this.positions = positions;
        this.attributes = attributes;
        this.roles = roles;
        this.subjects = subjects;
        this.transactions = transactions;
    }

    /** 一页成员及其分配。 */
    public List<MemberAssignment> page(TenantActor actor, long offset, int limit) {
        roles.requireTenantAdmin(actor.membershipId());
        List<Member> members = memberships.page(actor.tenantId(), offset, limit);
        if (members.isEmpty()) return List.of();
        List<UUID> ids = members.stream().map(Member::membershipId).toList();
        Map<UUID, List<DepartmentAssignmentRow>> departmentsOf = new HashMap<>();
        for (var row : assignments.departmentsOf(actor.tenantId(), ids)) {
            departmentsOf
                    .computeIfAbsent(row.membershipId(), key -> new ArrayList<>())
                    .add(row);
        }
        Map<UUID, List<UUID>> positionsOf = new HashMap<>();
        for (PositionAssignmentRow row : assignments.positionsOf(actor.tenantId(), ids)) {
            positionsOf
                    .computeIfAbsent(row.membershipId(), key -> new ArrayList<>())
                    .add(row.positionId());
        }
        return members.stream()
                .map(member -> {
                    var rows = departmentsOf.getOrDefault(member.membershipId(), List.of());
                    return new MemberAssignment(
                            member.membershipId(),
                            member.account(),
                            member.displayName(),
                            member.status(),
                            rows.stream()
                                    .filter(DepartmentAssignmentRow::primary)
                                    .map(DepartmentAssignmentRow::departmentId)
                                    .findFirst()
                                    .orElse(null),
                            rows.stream()
                                    .filter(row -> !row.primary())
                                    .map(DepartmentAssignmentRow::departmentId)
                                    .toList(),
                            positionsOf.getOrDefault(member.membershipId(), List.of()),
                            member.attributes());
                })
                .toList();
    }

    public long count(TenantActor actor) {
        roles.requireTenantAdmin(actor.membershipId());
        return memberships.count(actor.tenantId());
    }

    /**
     * 整体替换成员的分配。有部门时必须恰有一个主部门；主部门与兼任部门都传空时表示不属于任何部门。
     *
     * @param primaryDepartmentId 主部门，可为 null
     * @param departmentIds 兼任部门，不得包含主部门
     */
    public void assign(
            TenantActor actor,
            UUID membershipId,
            UUID primaryDepartmentId,
            List<UUID> departmentIds,
            List<UUID> positionIds,
            Map<String, Object> values) {
        roles.requireTenantAdmin(actor.membershipId());
        Set<UUID> concurrent = new LinkedHashSet<>(departmentIds);
        if ((primaryDepartmentId == null && !concurrent.isEmpty()) || concurrent.contains(primaryDepartmentId)) {
            throw new ForgeException(OrganizationErrorCode.INVALID_ASSIGNMENT);
        }
        Set<UUID> positionSet = new LinkedHashSet<>(positionIds);
        UUID tenantId = actor.tenantId();
        transactions.executeWithoutResult(status -> {
            departments.lockTenant(tenantId);
            if (!memberships.exists(tenantId, membershipId)) {
                throw new ForgeException(OrganizationErrorCode.MEMBERSHIP_NOT_FOUND);
            }
            Set<UUID> all = new LinkedHashSet<>(concurrent);
            if (primaryDepartmentId != null) all.add(primaryDepartmentId);
            if (!all.isEmpty() && departments.countExisting(tenantId, all) != all.size()) {
                throw new ForgeException(OrganizationErrorCode.DEPARTMENT_NOT_FOUND);
            }
            if (!positionSet.isEmpty() && positions.countExisting(tenantId, positionSet) != positionSet.size()) {
                throw new ForgeException(OrganizationErrorCode.POSITION_NOT_FOUND);
            }
            Map<String, Object> validated = attributes.validate(tenantId, values);
            assignments.clearDepartments(tenantId, membershipId);
            if (primaryDepartmentId != null) {
                assignments.insertDepartment(tenantId, membershipId, primaryDepartmentId, true);
            }
            for (UUID departmentId : concurrent)
                assignments.insertDepartment(tenantId, membershipId, departmentId, false);
            assignments.clearPositions(tenantId, membershipId);
            for (UUID positionId : positionSet) assignments.insertPosition(tenantId, membershipId, positionId);
            if (!memberships.updateAttributes(tenantId, membershipId, validated, actor.identityId())) {
                throw new ForgeException(OrganizationErrorCode.MEMBERSHIP_NOT_FOUND);
            }
        });
        subjects.changed(List.of(membershipId));
    }
}
