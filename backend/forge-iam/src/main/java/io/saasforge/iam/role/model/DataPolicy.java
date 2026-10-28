package io.saasforge.iam.role.model;

/**
 * 角色在一个 Data Resource 上的 Data Policy；本期只支持 Data Scope 预设。
 *
 * @param resource Data Resource 编码
 * @param scope Data Scope 预设：{@code self department department_and_below all}
 */
public record DataPolicy(String resource, String scope) {}
