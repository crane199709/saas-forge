package io.saasforge.iam.role.model;

import java.util.List;

/**
 * 条件编辑器中与 Data Resource 无关的可选项。
 *
 * @param subject 主体属性：平台按组织模型提供的属性与租户的 Extended Attribute
 * @param environment 环境属性
 * @param departments 部门，用于 {@code ltree} 取值
 * @param positions 岗位，用于成员岗位集合的取值
 */
public record ConditionOptions(
        List<ConditionAttribute> subject,
        List<ConditionAttribute> environment,
        List<ValueOption> departments,
        List<ValueOption> positions) {}
