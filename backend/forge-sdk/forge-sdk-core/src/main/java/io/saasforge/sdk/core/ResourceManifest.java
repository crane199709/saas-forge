package io.saasforge.sdk.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * @param roles 本服务的角色模板，iam 开通新租户时据此预置角色，可为 null（视为没有）
 * @param dataResources 本服务受数据权限保护的 Data Resource，可为 null（视为没有）
 * @param realm 服务所属的 Realm：{@value #PLATFORM_REALM} 表示 Platform Realm 服务（如 forge-platform），其权限码与数据资源
 *     只能授予平台角色；Tenant Realm 服务为 null（{@value #TENANT_REALM} 也规范化为 null）
 */
public record ResourceManifest(
        String service,
        String version,
        List<Permission> permissions,
        List<MenuNode> menus,
        // 没有角色模板时不输出该字段：iam 按 JSON 内容判断同一版本是否重复上报，加入本字段前存储的清单才能与之相同。
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<RoleTemplate> roles,
        // 同 roles：没有数据资源时不输出，已存储的旧清单与之按内容比较仍然相同。
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<DataResource> dataResources,
        // 同 roles：Tenant Realm 服务不输出该字段，加入本字段前存储的清单按内容比较仍然相同。
        @JsonInclude(JsonInclude.Include.NON_NULL) String realm) {
    /** Tenant Realm 服务的 Realm 名，与 {@code forge.security.realm} 的取值一致。 */
    public static final String TENANT_REALM = "tenant";

    /** Platform Realm 服务的 Realm 名，与 {@code forge.security.realm} 的取值一致。 */
    public static final String PLATFORM_REALM = "platform";

    // 长度上限与 iam 表的列宽一致。
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:._-]{0,127}");
    private static final Pattern ROLE_CODE = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    // 与 iam 预置角色的保留编码一致，模板不能占用。
    private static final Set<String> RESERVED_ROLE_CODES = Set.of("owner", "tenant-admin");
    private static final Pattern DATA_RESOURCE_CODE = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    // 表名、列名与属性编码会进入 SQL 或属性全名，只允许普通标识符。
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    // 与 forge-sdk-abac 的 AttributeType 一一对应；core 不依赖 abac，这里按名称约定。
    private static final Set<String> DATA_TYPES = Set.of("string", "number", "boolean", "uuid", "ltree");
    private static final int MAX_KEY = 64;
    private static final int MAX_NAME = 120;

    /** 复制为不可变列表，已生成的清单不能被调用方修改。 */
    public ResourceManifest {
        permissions = List.copyOf(permissions);
        menus = List.copyOf(menus);
        roles = roles == null ? List.of() : List.copyOf(roles);
        dataResources = dataResources == null ? List.of() : List.copyOf(dataResources);
        realm = TENANT_REALM.equals(realm) ? null : realm;
    }

    /**
     * Tenant Realm 服务的清单。
     *
     * @param service 服务名
     * @param version 清单版本
     * @param permissions 全部 Permission Code
     * @param menus 菜单树
     * @param roles 角色模板
     * @param dataResources 数据资源
     */
    public ResourceManifest(
            String service,
            String version,
            List<Permission> permissions,
            List<MenuNode> menus,
            List<RoleTemplate> roles,
            List<DataResource> dataResources) {
        this(service, version, permissions, menus, roles, dataResources, null);
    }

    /**
     * 没有 Data Resource 的清单。
     *
     * @param service 服务名
     * @param version 清单版本
     * @param permissions 全部 Permission Code
     * @param menus 菜单树
     * @param roles 角色模板
     */
    public ResourceManifest(
            String service,
            String version,
            List<Permission> permissions,
            List<MenuNode> menus,
            List<RoleTemplate> roles) {
        this(service, version, permissions, menus, roles, List.of(), null);
    }

    /**
     * 没有角色模板的清单。
     *
     * @param service 服务名
     * @param version 清单版本
     * @param permissions 全部 Permission Code
     * @param menus 菜单树
     */
    public ResourceManifest(String service, String version, List<Permission> permissions, List<MenuNode> menus) {
        this(service, version, permissions, menus, List.of(), List.of(), null);
    }

    /**
     * 是否为 Platform Realm 服务的清单。
     *
     * @return {@link #realm()} 为 {@value #PLATFORM_REALM} 时为 true
     */
    @JsonIgnore
    public boolean platform() {
        return PLATFORM_REALM.equals(realm);
    }

    /**
     * 检查清单是否完整、自洽。SDK 生成清单时与 iam 接收上报时执行同一套规则，CI 或其他客户端直接上报也不例外。
     *
     * <p>规则：服务名与版本非空；权限码格式合法且不重复，名称非空；菜单编码不重复；菜单引用的权限码必须在本清单中声明；
     * 叶子节点必须同时有 {@code app} 与 {@code path}，分组节点不能带路由，Shell 才能据此生成导航与路由；
     * 角色模板编码为小写字母、数字与连字符且不重复、不占用预置角色编码，名称非空，引用的权限码必须在本清单中声明；
     * Data Resource 的规则见 {@link #validateDataResources(List)}；Realm 只能是 Tenant 或 Platform，Platform Realm 服务不声明角色模板
     * （模板只用于开通租户）。
     *
     * @throws IllegalArgumentException 违反任一规则，消息说明具体位置
     */
    public void validate() {
        requireText(service, MAX_KEY, "service");
        requireText(version, MAX_KEY, "version");
        if (realm != null && !platform()) throw new IllegalArgumentException("Unknown realm: " + realm);
        if (platform() && !roles.isEmpty()) {
            throw new IllegalArgumentException("Platform Realm manifest cannot declare role templates");
        }
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
        validateRoles(codes);
        validateDataResources(dataResources);
    }

    /**
     * 检查 Data Resource 声明：编码为小写字母开头的小写字母、数字、{@code _ . -} 且不重复，名称非空；表名不重复；
     * 至少一个属性，属性编码与列名为普通标识符且在资源内不重复，类型为 {@code string number boolean uuid ltree} 之一。
     * 数据权限拦截器只读取清单文件中的数据资源，不生成整份清单，因此单独提供。
     *
     * @param resources 待检查的数据资源
     * @throws IllegalArgumentException 违反任一规则，消息说明具体位置
     */
    public static void validateDataResources(List<DataResource> resources) {
        Set<String> resourceCodes = new HashSet<>();
        Set<String> tables = new HashSet<>();
        for (DataResource resource : resources) {
            if (resource.code() == null
                    || !DATA_RESOURCE_CODE.matcher(resource.code()).matches()) {
                throw new IllegalArgumentException("Invalid Data Resource code: " + resource.code());
            }
            if (!resourceCodes.add(resource.code())) {
                throw new IllegalArgumentException("Duplicate Data Resource code: " + resource.code());
            }
            requireText(resource.name(), MAX_NAME, "name of Data Resource " + resource.code());
            requireIdentifier(resource.table(), "table of Data Resource " + resource.code());
            if (!tables.add(resource.table().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Table " + resource.table() + " is declared by two Data Resources");
            }
            if (resource.attributes().isEmpty()) {
                throw new IllegalArgumentException("Data Resource " + resource.code() + " declares no attributes");
            }
            Set<String> attributeCodes = new HashSet<>();
            for (DataAttribute attribute : resource.attributes()) {
                String where = resource.code() + "." + attribute.code();
                requireIdentifier(attribute.code(), "attribute code of Data Resource " + resource.code());
                if (!attributeCodes.add(attribute.code())) {
                    throw new IllegalArgumentException("Duplicate Data Resource attribute: " + where);
                }
                requireText(attribute.name(), MAX_NAME, "name of attribute " + where);
                if (!DATA_TYPES.contains(attribute.type())) {
                    throw new IllegalArgumentException("Unknown type of attribute " + where + ": " + attribute.type());
                }
                requireIdentifier(attribute.column(), "column of attribute " + where);
            }
        }
    }

    private void validateRoles(Set<String> codes) {
        Set<String> roleCodes = new HashSet<>();
        for (RoleTemplate role : roles) {
            if (role.code() == null
                    || !ROLE_CODE.matcher(role.code()).matches()
                    || RESERVED_ROLE_CODES.contains(role.code())) {
                throw new IllegalArgumentException("Invalid role template code: " + role.code());
            }
            if (!roleCodes.add(role.code())) {
                throw new IllegalArgumentException("Duplicate role template code: " + role.code());
            }
            requireText(role.name(), MAX_NAME, "name of role template " + role.code());
            for (String permission : role.permissions()) {
                if (!codes.contains(permission)) {
                    throw new IllegalArgumentException(
                            "Role template " + role.code() + " grants undeclared Permission Code: " + permission);
                }
            }
        }
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
            if (!leaf && !node.actions().isEmpty()) {
                throw new IllegalArgumentException("Menu " + node.code() + " actions are only allowed on leaf nodes");
            }
            Set<String> actions = new HashSet<>();
            for (String action : node.actions()) {
                if (!codes.contains(action)) {
                    throw new IllegalArgumentException("Menu " + node.code() + " has undeclared action: " + action);
                }
                if (!actions.add(action)) {
                    throw new IllegalArgumentException("Duplicate action in menu " + node.code() + ": " + action);
                }
                if (action.equals(node.permission())) {
                    throw new IllegalArgumentException(
                            "Menu " + node.code() + " action repeats its permission: " + action);
                }
            }
            validateMenus(node.children(), codes, menuCodes);
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Manifest " + name + " is not a plain identifier: " + value);
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
     * 角色模板：业务服务建议的角色及其授权。iam 在新租户开通时据此建立普通的自定义角色，之后由 Tenant Admin 自由编辑或删除。
     *
     * @param code 角色编码，租户内唯一
     * @param name 展示名称
     * @param permissions 授予的 Permission Code，须在同一清单中声明
     */
    public record RoleTemplate(String code, String name, List<String> permissions) {
        /** 授权缺省为空列表。 */
        public RoleTemplate {
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }

    /**
     * Data Resource：一类受数据权限保护的业务数据，对应一张表；Data Policy 只能引用这里声明的属性。
     *
     * <p>使用 Data Scope 预设须声明相应属性：「本人」需要 {@code uuid} 类型的 {@code ownerId}（数据归属的 Membership），
     * 「本部门」「本部门及下级」需要 {@code ltree} 类型的 {@code departmentPath}（数据归属部门的路径）。
     *
     * @param code 资源编码，全平台唯一，如 {@code order}
     * @param name 展示名称
     * @param table 资源所在的表，SDK 对该表的查询、更新与删除注入数据权限条件
     * @param attributes 可用于策略判断的资源属性
     */
    public record DataResource(String code, String name, String table, List<DataAttribute> attributes) {
        /** 属性缺省为空列表。 */
        public DataResource {
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
        }
    }

    /**
     * Data Resource 的一个属性，在策略中写作 {@code resource.<code>}。
     *
     * @param code 属性编码，如 {@code ownerId}
     * @param name 展示名称
     * @param type 值类型：{@code string number boolean uuid ltree}
     * @param column 对应的表列
     */
    public record DataAttribute(String code, String name, String type, String column) {}

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
     * @param actions 叶子页面内的操作权限码，仅用于角色授权树展示，缺省为空
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MenuNode(
            String code,
            String name,
            String icon,
            String app,
            String path,
            String permission,
            List<MenuNode> children,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> actions) {
        /** 子节点与页面操作缺省为空列表。 */
        public MenuNode {
            children = children == null ? List.of() : List.copyOf(children);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        /**
         * 不声明页面操作的菜单，保留已有调用方的构造方式。
         *
         * @param code 菜单编码
         * @param name 展示名称
         * @param icon 图标名
         * @param app 叶子所属 Sub-App
         * @param path Sub-App 内的路由
         * @param permission 查看节点所需的权限码
         * @param children 子节点
         */
        public MenuNode(
                String code,
                String name,
                String icon,
                String app,
                String path,
                String permission,
                List<MenuNode> children) {
            this(code, name, icon, app, path, permission, children, List.of());
        }

        /**
         * 替换子节点后的副本，如按权限裁剪菜单。
         *
         * @param children 新的子节点
         * @return 其余字段相同的节点
         */
        public MenuNode withChildren(List<MenuNode> children) {
            return new MenuNode(code, name, icon, app, path, permission, children, actions);
        }
    }
}
