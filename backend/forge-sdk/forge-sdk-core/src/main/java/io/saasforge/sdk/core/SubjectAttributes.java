package io.saasforge.sdk.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Membership 的组织属性，作为数据权限（ABAC）的主体属性，随 {@link MembershipGrants} 按版本发布（ADR-0006）。
 *
 * @param departmentPaths 主部门与兼任部门的 {@code ltree} 路径
 * @param primaryDepartmentPath 主部门路径；未分配部门时为 null
 * @param positionIds 担任的 Position
 * @param attributes Extended Attribute 值，按属性编码；值为 {@link String}、{@link Number} 或 {@link Boolean}
 * @param attributeTypes 租户全部 Extended Attribute 定义的类型（{@code string number boolean}），按属性编码；
 *     业务服务据此校验引用扩展属性的 Data Policy，未填写的属性也要有类型
 */
public record SubjectAttributes(
        Set<String> departmentPaths,
        String primaryDepartmentPath,
        Set<UUID> positionIds,
        Map<String, Object> attributes,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> attributeTypes) {

    /** 未分配任何部门、岗位与扩展属性。 */
    public static final SubjectAttributes NONE = new SubjectAttributes(Set.of(), null, Set.of(), Map.of(), Map.of());

    /** 复制为不可变集合；缺失的集合视为空，兼容不带主体属性的旧明细。 */
    public SubjectAttributes {
        departmentPaths = departmentPaths == null ? Set.of() : Set.copyOf(departmentPaths);
        positionIds = positionIds == null ? Set.of() : Set.copyOf(positionIds);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        attributeTypes = attributeTypes == null ? Map.of() : Map.copyOf(attributeTypes);
    }

    /**
     * 转为 ABAC 求值与 SQL 编译使用的主体上下文，键为带命名空间的属性全名：
     *
     * <ul>
     *   <li>{@code subject.membershipId}：UUID</li>
     *   <li>{@code subject.departmentPaths}：LTREE 集合，主部门与兼任部门</li>
     *   <li>{@code subject.primaryDepartmentPath}：LTREE，未分配部门时没有该键</li>
     *   <li>{@code subject.positionIds}：UUID 集合</li>
     *   <li>{@code subject.attributes.<编码>}：Extended Attribute 值，未填写的属性没有该键</li>
     * </ul>
     *
     * @param membershipId 当前 Membership
     * @return 可变的新 Map，调用方可再加入环境属性
     */
    public Map<String, Object> toContext(UUID membershipId) {
        var context = new HashMap<String, Object>();
        context.put("subject.membershipId", membershipId);
        // 排序使同一主体每次得到相同的上下文，编译出的 SQL 参数顺序稳定。
        context.put("subject.departmentPaths", departmentPaths.stream().sorted().toList());
        if (primaryDepartmentPath != null) {
            context.put("subject.primaryDepartmentPath", primaryDepartmentPath);
        }
        context.put("subject.positionIds", positionIds.stream().sorted().toList());
        attributes.forEach((code, value) -> context.put("subject.attributes." + code, value));
        return context;
    }
}
