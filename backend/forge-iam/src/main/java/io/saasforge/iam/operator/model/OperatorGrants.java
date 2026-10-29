package io.saasforge.iam.operator.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operator 当前的平台角色与权限。
 *
 * @param roles 平台角色编码
 * @param permissions 有效的平台 Permission Code，多个角色取并集
 * @param dataPolicies 按平台 Data Resource 编码分组的 Data Policy（JSON 表达式树），由 Platform Realm 服务取并集
 */
public record OperatorGrants(Set<String> roles, Set<String> permissions, Map<String, List<String>> dataPolicies) {}
