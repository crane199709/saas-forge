package io.saasforge.iam.role.model;

/**
 * 条件编辑器中可选的一个取值。
 *
 * @param value 写入条件的值，如部门的 ltree 路径、岗位 id
 * @param label 显示名，部门为自根部门起的完整名称
 */
public record ValueOption(String value, String label) {}
