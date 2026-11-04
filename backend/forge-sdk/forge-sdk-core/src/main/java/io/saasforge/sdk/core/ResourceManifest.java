package io.saasforge.sdk.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * @param dictionaries 本服务声明的 Dictionary，可为 null（视为没有）
 * @param parameters 本服务声明的 Parameter，可为 null（视为没有）
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
        @JsonInclude(JsonInclude.Include.NON_NULL) String realm,
        // 同 roles：没有字典或参数的服务不输出这两节，已存储的旧清单按内容比较仍然相同，无需升版本。
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Dictionary> dictionaries,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Parameter> parameters) {
    /** Tenant Realm 服务的 Realm 名，与 {@code forge.security.realm} 的取值一致。 */
    public static final String TENANT_REALM = "tenant";

    /** Platform Realm 服务的 Realm 名，与 {@code forge.security.realm} 的取值一致。 */
    public static final String PLATFORM_REALM = "platform";

    /** Tenant 自定义字典项编码的前缀，服务声明的内置项不能使用。 */
    public static final String CUSTOM_ITEM_PREFIX = "custom.";

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
    // 字典与参数编码不含冒号：跨服务引用写作「服务:编码」，编码中不能再出现分隔符。
    private static final Pattern REFERENCE_CODE = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    // 租户自定义项编码为 custom. 加租户填写的部分，总长同样受 64 的列宽约束。
    private static final Pattern ITEM_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Set<String> PARAMETER_TYPES =
            Set.of("string", "integer", "decimal", "duration", "boolean", "dictionary");
    private static final int MAX_KEY = 64;
    private static final int MAX_NAME = 120;

    /** 复制为不可变列表，已生成的清单不能被调用方修改。 */
    public ResourceManifest {
        permissions = List.copyOf(permissions);
        menus = List.copyOf(menus);
        roles = roles == null ? List.of() : List.copyOf(roles);
        dataResources = dataResources == null ? List.of() : List.copyOf(dataResources);
        realm = TENANT_REALM.equals(realm) ? null : realm;
        dictionaries = dictionaries == null ? List.of() : List.copyOf(dictionaries);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /**
     * 没有 Dictionary 与 Parameter 的清单。
     *
     * @param service 服务名
     * @param version 清单版本
     * @param permissions 全部 Permission Code
     * @param menus 菜单树
     * @param roles 角色模板
     * @param dataResources 数据资源
     * @param realm 服务所属的 Realm
     */
    public ResourceManifest(
            String service,
            String version,
            List<Permission> permissions,
            List<MenuNode> menus,
            List<RoleTemplate> roles,
            List<DataResource> dataResources,
            String realm) {
        this(service, version, permissions, menus, roles, dataResources, realm, List.of(), List.of());
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
     * （模板只用于开通租户）；Dictionary 编码为小写字母开头的小写字母、数字、{@code _ . -} 且不重复，名称非空，内置项编码合法、
     * 不以 {@value #CUSTOM_ITEM_PREFIX} 开头，编码与名称在字典内都不重复；Parameter 编码规则同 Dictionary 且不重复，名称非空，
     * 类型合法，约束只用于适用的类型且 {@code min} 不大于 {@code max}，默认值存在且满足类型与约束，{@code dictionary} 类型引用
     * 本清单声明的字典且默认值是其内置项。
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
        Map<String, Dictionary> declared = validateDictionaries();
        validateParameters(declared);
    }

    private Map<String, Dictionary> validateDictionaries() {
        Map<String, Dictionary> declared = new HashMap<>();
        for (Dictionary dictionary : dictionaries) {
            if (dictionary.code() == null
                    || !REFERENCE_CODE.matcher(dictionary.code()).matches()) {
                throw new IllegalArgumentException("Invalid Dictionary code: " + dictionary.code());
            }
            if (declared.putIfAbsent(dictionary.code(), dictionary) != null) {
                throw new IllegalArgumentException("Duplicate Dictionary code: " + dictionary.code());
            }
            requireText(dictionary.name(), MAX_NAME, "name of Dictionary " + dictionary.code());
            Set<String> itemCodes = new HashSet<>();
            // 导入按名称匹配字典项，同一字典内名称重复就无法确定对应哪一项。
            Set<String> itemNames = new HashSet<>();
            for (DictionaryItem item : dictionary.items()) {
                if (item.code() == null
                        || !ITEM_CODE.matcher(item.code()).matches()
                        || item.code().startsWith(CUSTOM_ITEM_PREFIX)) {
                    throw new IllegalArgumentException("Invalid item code in Dictionary " + dictionary.code() + ": "
                            + item.code() + " (built-in items cannot start with " + CUSTOM_ITEM_PREFIX + ")");
                }
                if (!itemCodes.add(item.code())) {
                    throw new IllegalArgumentException(
                            "Duplicate item code in Dictionary " + dictionary.code() + ": " + item.code());
                }
                requireText(item.name(), MAX_NAME, "name of item " + dictionary.code() + "." + item.code());
                if (!itemNames.add(item.name())) {
                    throw new IllegalArgumentException(
                            "Duplicate item name in Dictionary " + dictionary.code() + ": " + item.name());
                }
            }
        }
        return declared;
    }

    private void validateParameters(Map<String, Dictionary> declared) {
        Set<String> parameterCodes = new HashSet<>();
        for (Parameter parameter : parameters) {
            String where = "Parameter " + parameter.code();
            if (parameter.code() == null
                    || !REFERENCE_CODE.matcher(parameter.code()).matches()) {
                throw new IllegalArgumentException("Invalid Parameter code: " + parameter.code());
            }
            if (!parameterCodes.add(parameter.code())) {
                throw new IllegalArgumentException("Duplicate Parameter code: " + parameter.code());
            }
            requireText(parameter.name(), MAX_NAME, "name of " + where);
            if (!PARAMETER_TYPES.contains(parameter.type())) {
                throw new IllegalArgumentException("Unknown type of " + where + ": " + parameter.type());
            }
            if (parameter.defaultValue() == null) throw new IllegalArgumentException(where + " has no default");
            String type = parameter.type();
            boolean ranged = type.equals("integer") || type.equals("decimal") || type.equals("duration");
            // 不适用的约束不能静默忽略，否则声明者以为有限制而实际没有。
            if (!ranged && (parameter.min() != null || parameter.max() != null)) {
                throw new IllegalArgumentException(
                        where + ": min/max are only for the integer, decimal and duration types");
            }
            if (!type.equals("string") && parameter.maxLength() != null) {
                throw new IllegalArgumentException(where + ": maxLength is only for the string type");
            }
            if (!type.equals("dictionary") && parameter.dictionary() != null) {
                throw new IllegalArgumentException(where + ": dictionary is only for the dictionary type");
            }
            String defaultOf = "Default of " + where;
            switch (type) {
                case "string" -> {
                    if (!(parameter.defaultValue() instanceof String value)) {
                        throw new IllegalArgumentException(
                                defaultOf + " is not a valid string: " + parameter.defaultValue());
                    }
                    if (parameter.maxLength() != null) {
                        if (parameter.maxLength() <= 0)
                            throw new IllegalArgumentException(where + " has invalid maxLength");
                        if (value.length() > parameter.maxLength()) {
                            throw new IllegalArgumentException(defaultOf + " is longer than maxLength");
                        }
                    }
                }
                case "boolean" -> {
                    if (!(parameter.defaultValue() instanceof Boolean)) {
                        throw new IllegalArgumentException(
                                defaultOf + " is not a valid boolean: " + parameter.defaultValue());
                    }
                }
                case "dictionary" -> {
                    Dictionary dictionary = declared.get(parameter.dictionary());
                    if (dictionary == null) {
                        throw new IllegalArgumentException(
                                where + " refers to undeclared Dictionary: " + parameter.dictionary());
                    }
                    if (dictionary.items().stream()
                            .noneMatch(item -> item.code().equals(parameter.defaultValue()))) {
                        throw new IllegalArgumentException(defaultOf + " is not an item of Dictionary "
                                + dictionary.code() + ": " + parameter.defaultValue());
                    }
                }
                default -> {
                    Comparable<Object> value = comparable(type, parameter.defaultValue(), defaultOf);
                    Comparable<Object> min =
                            parameter.min() == null ? null : comparable(type, parameter.min(), "Min of " + where);
                    Comparable<Object> max =
                            parameter.max() == null ? null : comparable(type, parameter.max(), "Max of " + where);
                    if (min != null && max != null && min.compareTo(max) > 0) {
                        throw new IllegalArgumentException(where + " has min greater than max");
                    }
                    if (min != null && value.compareTo(min) < 0) {
                        throw new IllegalArgumentException(defaultOf + " is less than min");
                    }
                    if (max != null && value.compareTo(max) > 0) {
                        throw new IllegalArgumentException(defaultOf + " is greater than max");
                    }
                }
            }
        }
    }

    // integer、decimal 统一按 BigDecimal 比较，duration 按 Duration 比较；JSON 反序列化给出的数字类型因解析器而异。
    @SuppressWarnings("unchecked")
    private static Comparable<Object> comparable(String type, Object raw, String what) {
        Comparable<?> value = switch (type) {
            case "integer" ->
                raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof BigInteger
                        ? new BigDecimal(raw.toString())
                        : null;
            case "decimal" -> raw instanceof Number ? new BigDecimal(raw.toString()) : null;
            default -> {
                try {
                    yield raw instanceof String text ? Duration.parse(text) : null;
                } catch (DateTimeParseException error) {
                    yield null;
                }
            }
        };
        if (value == null) throw new IllegalArgumentException(what + " is not a valid " + type + ": " + raw);
        return (Comparable<Object>) value;
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
     * Dictionary：一组由编码与名称组成的可选值，只有一层。业务数据保存字典项的编码。
     *
     * @param code 字典编码，本服务内唯一；iam 以「服务 + 编码」区分不同服务的同名字典
     * @param name 展示名称
     * @param extensible 是否为 Extensible Dictionary：Tenant 可新增自定义项、停用内置项、调整排序与显示名；否则对 Tenant 只读
     * @param items 服务声明的内置项，按展示顺序排列
     */
    public record Dictionary(String code, String name, Boolean extensible, List<DictionaryItem> items) {
        /** 未声明 {@code extensible} 视为固定字典，内置项缺省为空列表。 */
        public Dictionary {
            extensible = Boolean.TRUE.equals(extensible);
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * 服务声明的内置字典项。
     *
     * @param code 字典项编码，字典内唯一，不能以 {@value ResourceManifest#CUSTOM_ITEM_PREFIX} 开头（该前缀留给租户自定义项）
     * @param name 展示名称，字典内不重复
     */
    public record DictionaryItem(String code, String name) {}

    /**
     * Parameter：有类型、有默认值的设置项。
     *
     * <p>取值在 JSON 中的写法随类型而定：{@code string duration dictionary} 为字符串（{@code duration} 为 ISO-8601，如
     * {@code PT30M}），{@code integer decimal} 为数字，{@code boolean} 为布尔值。{@code min}/{@code max} 只用于
     * {@code integer decimal duration}，{@code maxLength} 只用于 {@code string}，{@code dictionary} 只用于 {@code dictionary}。
     *
     * @param code 参数编码，本服务内唯一
     * @param name 展示名称
     * @param type 类型：{@code string integer decimal duration boolean dictionary}
     * @param dictionary {@code dictionary} 类型取值所属的字典编码，须在同一清单中声明；其他类型为 null
     * @param defaultValue 默认值，必须满足类型与约束；JSON 字段名为 {@code default}
     * @param min 最小值（含），可为 null
     * @param max 最大值（含），可为 null
     * @param maxLength {@code string} 类型的最大长度，可为 null
     * @param tenantEditable Tenant 是否可覆盖默认值
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Parameter(
            String code,
            String name,
            String type,
            String dictionary,
            @JsonProperty("default") Object defaultValue,
            Object min,
            Object max,
            Integer maxLength,
            Boolean tenantEditable) {
        /** 未声明 {@code tenantEditable} 视为不可修改。 */
        public Parameter {
            tenantEditable = Boolean.TRUE.equals(tenantEditable);
        }
    }

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
