package io.saasforge.iam.role.model;

/**
 * 自定义条件可引用的一个属性，供条件编辑器按类型限制操作符与取值控件。
 *
 * @param name 带命名空间的全名，如 {@code resource.amount}、{@code subject.attributes.level}、{@code env.now}
 * @param label 显示名：资源属性取清单中的名称，扩展属性取租户定义的名称；平台提供的主体与环境属性为 null，由前端翻译
 * @param type {@code string number boolean uuid ltree}
 * @param multiValued 是否为集合值，如成员的部门路径集合
 */
public record ConditionAttribute(String name, String label, String type, boolean multiValued) {}
