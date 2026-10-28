package io.saasforge.iam.membership.model;

import java.util.Set;

/**
 * Membership 当前的角色与权限。
 *
 * @param roles 角色编码
 * @param permissions 有效的 Permission Code，多个角色取并集
 */
public record RoleGrants(Set<String> roles, Set<String> permissions) {}
