package io.saasforge.sdk.abac;

/**
 * Data Scope 预设，以表达式树模板提供，与自定义 Data Policy 走同一套解析、编译与求值。
 *
 * <p>模板约定的属性：
 *
 * <ul>
 *   <li>{@code subject.membershipId}：UUID，当前 Membership</li>
 *   <li>{@code subject.departmentPaths}：LTREE 集合，当前 Membership 的主部门与兼任部门路径</li>
 *   <li>{@code resource.ownerId}：UUID，数据归属的 Membership</li>
 *   <li>{@code resource.departmentPath}：LTREE，数据归属的部门路径</li>
 * </ul>
 *
 * <p>使用某个预设的 Data Resource 必须在属性目录中声明它引用的资源属性，否则加载时报错。
 */
public enum DataScope {
    /** 本人：数据归属于当前 Membership。 */
    SELF("{\"attr\":\"resource.ownerId\",\"op\":\"eq\",\"value\":{\"ref\":\"subject.membershipId\"}}"),
    /** 本部门：数据归属部门是当前 Membership 的主部门或兼任部门之一，不含下级部门。 */
    DEPARTMENT("{\"attr\":\"resource.departmentPath\",\"op\":\"in\",\"value\":{\"ref\":\"subject.departmentPaths\"}}"),
    /** 本部门及下级：数据归属部门在当前 Membership 任一所在部门的子树下。 */
    DEPARTMENT_AND_BELOW(
            "{\"attr\":\"resource.departmentPath\",\"op\":\"under\",\"value\":{\"ref\":\"subject.departmentPaths\"}}"),
    /** 全部：不限制。 */
    ALL("{\"and\":[]}");

    private final String template;

    DataScope(String template) {
        this.template = template;
    }

    /**
     * 预设对应的 JSON 表达式树。
     *
     * @return 可直接存为 Data Policy 的 JSON
     */
    public String template() {
        return template;
    }

    /**
     * 按属性目录解析并校验预设。
     *
     * @param catalog 可引用的属性
     * @return 表达式树
     * @throws PolicyValidationException 目录缺少预设引用的属性或类型不符
     */
    public Expression expression(AttributeCatalog catalog) {
        return PolicyParser.parse(template, catalog);
    }
}
