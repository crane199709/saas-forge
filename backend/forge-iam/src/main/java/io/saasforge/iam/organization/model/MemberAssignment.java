package io.saasforge.iam.organization.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 成员及其组织分配。
 *
 * @param membershipId Membership 标识
 * @param account Identity 账号
 * @param displayName Identity 昵称
 * @param status Membership 状态
 * @param primaryDepartmentId 主部门；未分配时为 null
 * @param departmentIds 兼任部门，不含主部门
 * @param positionIds 担任的岗位
 * @param attributes Extended Attribute 值
 */
public record MemberAssignment(
        UUID membershipId,
        String account,
        String displayName,
        String status,
        UUID primaryDepartmentId,
        List<UUID> departmentIds,
        List<UUID> positionIds,
        Map<String, Object> attributes) {}
