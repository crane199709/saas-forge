package io.saasforge.iam.navigation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Navigation Layout：导航节点的位置，两端格式相同。只引用节点，不校验引用是否存在：已下线的页面要保留位置。
 *
 * @param nodes 顶层节点
 */
public record NavigationLayout(List<Node> nodes) {
    /**
     * 布局节点，恰有 {@code page}、{@code menu}、{@code group} 之一。
     *
     * @param page Shell Page 的页面键
     * @param menu 清单菜单编码，叶子或分组
     * @param group 自定义分组 id，由前端生成
     * @param name 自定义分组名称
     * @param children 子节点，Shell Page 没有
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(String page, String menu, String group, String name, List<Node> children) {}
}
