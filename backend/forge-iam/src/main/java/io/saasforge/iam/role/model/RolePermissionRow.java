package io.saasforge.iam.role.model;

import java.util.UUID;

/** 角色授予的一个有效 Permission Code。 */
public record RolePermissionRow(UUID roleId, String code) {}
