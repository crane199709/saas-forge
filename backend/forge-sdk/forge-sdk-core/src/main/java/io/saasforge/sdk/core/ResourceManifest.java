package io.saasforge.sdk.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 业务服务某一版本向 iam 申报的 Resource Manifest，由 SDK 生成、iam 存储，是两者之间的接口契约。
 *
 * <p>同一 {@code service + version} 的内容不可变：iam 拒绝以相同版本上报不同内容，改动清单必须升级版本。
 *
 * @param service 服务名，与 {@code spring.application.name} 一致
 * @param version 清单版本
 * @param permissions 本服务的全部 Permission Code，按编码排序
 * @param menus 本服务的菜单树，按申报顺序展示
 */
public record ResourceManifest(String service, String version, List<Permission> permissions, List<MenuNode> menus) {
    // 长度上限与 iam 表的列宽一致。
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:._-]{0,127}");
    private static final int MAX_KEY = 64;
    private static final int MAX_NAME = 120;

    /** 复制为不可变列表，已生成的清单不能被调用方修改。 */
    public ResourceManifest {
        permissions = List.copyOf(permissions);
        menus = List.copyOf(menus);
    }

    /**
     * 检查清单是否完整、自洽。SDK 生成清单时与 iam 接收上报时执行同一套规则，CI 或其他客户端直接上报也不例外。
     *
     * <p>规则：服务名与版本非空；权限码格式合法且不重复，名称非空；菜单编码不重复；菜单引用的权限码必须在本清单中声明；
     * 叶子节点必须同时有 {@code app} 与 {@code path}，分组节点不能带路由，Shell 才能据此生成导航与路由。
     *
     * @throws IllegalArgumentException 违反任一规则，消息说明具体位置
     */
    public void validate() {
        requireText(service, MAX_KEY, "service");
        requireText(version, MAX_KEY, "version");
        Set<String> codes = new HashSet<>();
        for (Permission permission : permissions) {
            if (permission.code() == null || !CODE.matcher(permission.code()).matches()) {
                throw new IllegalArgumentException("Invalid Permission Code: " + permission.code());
            }
            if (!codes.add(permission.code())) {
                throw new IllegalArgumentException("Duplicate Permission Code: " + permission.code());
            }
            requireText(permission.name(), MAX_NAME, "name of " + permission.code());
            if (permission.group() != null && permission.group().length() > MAX_NAME) {
                throw new IllegalArgumentException("Group of " + permission.code() + " is too long");
            }
        }
        validateMenus(menus, codes, new HashSet<>());
    }

    private static void validateMenus(List<MenuNode> nodes, Set<String> codes, Set<String> menuCodes) {
        for (MenuNode node : nodes) {
            requireText(node.code(), MAX_NAME, "menu code");
            requireText(node.name(), MAX_NAME, "name of menu " + node.code());
            if (!menuCodes.add(node.code())) throw new IllegalArgumentException("Duplicate menu code: " + node.code());
            if (node.permission() != null && !codes.contains(node.permission())) {
                throw new IllegalArgumentException(
                        "Menu " + node.code() + " requires undeclared Permission Code: " + node.permission());
            }
            boolean leaf = node.children().isEmpty();
            if (leaf != (node.app() != null && node.path() != null)) {
                throw new IllegalArgumentException(
                        "Menu " + node.code() + " must have either app and path or children");
            }
            validateMenus(node.children(), codes, menuCodes);
        }
    }

    private static void requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("Manifest " + name + " must be 1-" + maxLength + " characters");
        }
    }

    /**
     * 一个 Permission Code 及其展示信息。
     *
     * @param code 权限码，如 {@code order:approve}，全平台唯一
     * @param name 展示名称
     * @param group 展示分组，可为 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Permission(String code, String name, String group) {}

    /**
     * 菜单节点。叶子节点指向某个 Sub-App 的路由，分组节点只有子节点。
     *
     * @param code 菜单编码，本服务内唯一
     * @param name 展示名称
     * @param icon 图标名，可为 null
     * @param app 叶子节点所属 Sub-App 的应用编码，分组节点为 null
     * @param path 叶子节点在 Sub-App 内的相对路由，分组节点为 null
     * @param permission 查看该节点所需的 Permission Code，为 null 表示已登录成员均可见
     * @param children 子节点
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MenuNode(
            String code,
            String name,
            String icon,
            String app,
            String path,
            String permission,
            List<MenuNode> children) {
        /** 子节点缺省为空列表。 */
        public MenuNode {
            children = children == null ? List.of() : List.copyOf(children);
        }

        /**
         * 替换子节点后的副本，如按权限裁剪菜单。
         *
         * @param children 新的子节点
         * @return 其余字段相同的节点
         */
        public MenuNode withChildren(List<MenuNode> children) {
            return new MenuNode(code, name, icon, app, path, permission, children);
        }
    }
}
