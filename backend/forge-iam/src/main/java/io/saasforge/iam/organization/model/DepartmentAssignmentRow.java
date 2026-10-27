package io.saasforge.iam.organization.model;

import java.util.UUID;

/** 成员所在的一个部门；{@code path} 为 ltree 的文本形式。 */
public record DepartmentAssignmentRow(UUID membershipId, UUID departmentId, String path, boolean primary) {}
