package io.saasforge.iam.role.model;

import java.util.List;

/**
 * 角色编辑中可配置 Data Policy 的一个 Data Resource。
 *
 * @param scopes 该资源可用的 Data Scope 预设：预设引用的资源属性都已在清单中声明
 */
public record DataResourceOption(String code, String name, String service, List<String> scopes) {}
