package io.saasforge.iam.membership.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Membership 当前的角色与权限。
 *
 * @param roles 角色编码
 * @param permissions 有效的 Permission Code，多个角色取并集
 * @param dataPolicies 按 Data Resource 编码分组的 Data Policy（JSON 表达式树），由业务服务取并集
 */
public record RoleGrants(Set<String> roles, Set<String> permissions, Map<String, List<String>> dataPolicies) {}
