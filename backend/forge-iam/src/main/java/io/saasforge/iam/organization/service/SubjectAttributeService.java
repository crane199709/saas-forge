package io.saasforge.iam.organization.service;

import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.membership.service.SubjectAttributeSource;
import io.saasforge.iam.organization.mapper.AssignmentMapper;
import io.saasforge.iam.organization.mapper.ExtendedAttributeMapper;
import io.saasforge.iam.organization.model.DepartmentAssignmentRow;
import io.saasforge.iam.organization.model.ExtendedAttributeRow;
import io.saasforge.iam.organization.model.PositionAssignmentRow;
import io.saasforge.sdk.core.SubjectAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 由组织分配计算 Membership 的主体属性，iam 签发 Token 与递增版本时随权限明细一起发布。 */
@Service
class SubjectAttributeService implements SubjectAttributeSource {
    private final AssignmentMapper assignments;
    private final ExtendedAttributeMapper attributes;
    private final MembershipService memberships;

    SubjectAttributeService(
            AssignmentMapper assignments, ExtendedAttributeMapper attributes, MembershipService memberships) {
        this.assignments = assignments;
        this.attributes = attributes;
        this.memberships = memberships;
    }

    @Override
    public SubjectAttributes subjectOf(UUID tenantId, UUID membershipId) {
        List<DepartmentAssignmentRow> departments = assignments.departmentsOf(tenantId, List.of(membershipId));
        return new SubjectAttributes(
                departments.stream().map(DepartmentAssignmentRow::path).collect(Collectors.toSet()),
                departments.stream()
                        .filter(DepartmentAssignmentRow::primary)
                        .map(DepartmentAssignmentRow::path)
                        .findFirst()
                        .orElse(null),
                assignments.positionsOf(tenantId, List.of(membershipId)).stream()
                        .map(PositionAssignmentRow::positionId)
                        .collect(Collectors.toSet()),
                memberships.attributes(tenantId, membershipId),
                types(tenantId));
    }

    // 直接读定义而不经 ExtendedAttributeService：后者经 SubjectChangePublisher 依赖发布明细的服务，会形成 Bean 循环依赖。
    private Map<String, String> types(UUID tenantId) {
        Map<String, String> types = new HashMap<>();
        for (ExtendedAttributeRow definition : attributes.list(tenantId)) {
            types.put(definition.code(), definition.type());
        }
        return types;
    }
}
