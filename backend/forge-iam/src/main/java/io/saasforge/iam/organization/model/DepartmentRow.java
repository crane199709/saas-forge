package io.saasforge.iam.organization.model;

import java.util.UUID;

/** 部门查询投影；{@code path} 为 ltree 的文本形式。 */
public record DepartmentRow(UUID id, UUID parentId, String name, String path) {}
