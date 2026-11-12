package io.saasforge.sdk.manifest;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.DataResource;
import io.saasforge.sdk.core.ResourceManifest.Dictionary;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.ResourceManifest.Parameter;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.core.ResourceManifest.RoleTemplate;
import io.saasforge.sdk.core.ResourceManifest.SensitiveField;
import io.saasforge.sdk.security.RequiresPermission;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 生成服务的 Resource Manifest：扫描 {@link RequiresPermission} 得到 Permission Code，再合并清单文件
 * {@value #MANIFEST_LOCATION} 中的名称、分组、菜单树、角色模板、Data Resource、Dictionary、Parameter 与 Sensitive Field。服务启动时与 CI 中的 {@link ManifestCli} 共用。
 *
 * <p>清单文件格式：
 *
 * <pre>{@code
 * {
 *   "permissions": { "order:read": { "name": "查看订单", "group": "订单" },
 *     "order:create": { "name": "创建订单", "group": "订单" } },
 *   "menus": [ { "code": "orders", "name": "订单", "app": "demo", "path": "overview", "permission": "order:read",
 *     "actions": ["order:create"] } ],
 *   "roles": [ { "code": "order-viewer", "name": "订单查看员", "permissions": ["order:read"] } ],
 *   "dataResources": [ { "code": "order", "name": "订单", "table": "orders", "attributes": [
 *     { "code": "ownerId", "name": "归属成员", "type": "uuid", "column": "owner_id" } ] } ],
 *   "dictionaries": [ { "code": "customer-source", "name": "客户来源", "extensible": true,
 *     "items": [ { "code": "online", "name": "线上" } ] } ],
 *   "parameters": [ { "code": "order.auto-close", "name": "未支付订单自动关闭时长", "type": "duration",
 *     "default": "PT30M", "min": "PT5M", "max": "P3D", "tenantEditable": true } ],
 *   "sensitiveFields": [ { "path": "customer.phone", "grade": "L3", "defaultRule": "phone" } ]
 * }
 * }</pre>
 *
 * <p>Platform Realm 服务（如 forge-platform）在文件中另写 {@code "realm": "platform"}：其权限码与数据资源只出现在平台角色中。
 *
 * <p>清单文件可额外声明只用于前端按钮、没有后端接口的 Permission Code；未在文件中命名的编码以编码本身作名称。
 */
public final class ResourceManifestCollector {
    /** 清单文件在 classpath 中的位置。 */
    public static final String MANIFEST_LOCATION = "META-INF/forge/manifest.json";

    // 拼错的字段名不能被静默忽略，否则名称或权限要求会悄悄丢失。
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private ResourceManifestCollector() {}

    /**
     * 扫描基础包并读取 classpath 中的清单文件。
     *
     * @param service 服务名
     * @param version 清单版本
     * @param basePackage 扫描 {@link RequiresPermission} 与 {@link io.saasforge.sdk.security.SensitiveField} 的基础包
     * @param classLoader 加载业务类与清单文件的类加载器
     * @return 合并后的清单
     * @throws IllegalStateException 清单文件无法解析，或清单违反 {@link ResourceManifest#validate()} 的规则
     */
    public static ResourceManifest collect(
            String service, String version, String basePackage, ClassLoader classLoader) {
        return collect(service, version, basePackage, classLoader, MANIFEST_LOCATION);
    }

    static ResourceManifest collect(
            String service, String version, String basePackage, ClassLoader classLoader, String location) {
        ManifestFile file = read(classLoader, location);
        Set<String> codes = new TreeSet<>(scan(basePackage, classLoader));
        codes.addAll(file.permissions().keySet());
        List<Permission> permissions = new ArrayList<>();
        for (String code : codes) {
            PermissionInfo info = file.permissions().get(code);
            String name = info == null || info.name() == null || info.name().isBlank() ? code : info.name();
            permissions.add(new Permission(code, name, info == null ? null : info.group()));
        }
        List<SensitiveField> sensitiveFields = new ArrayList<>(file.sensitiveFields());
        sensitiveFields.addAll(scanSensitiveFields(basePackage, classLoader));
        sensitiveFields.sort(
                Comparator.comparing(SensitiveField::path, Comparator.nullsFirst(Comparator.naturalOrder())));
        var manifest = new ResourceManifest(
                service,
                version,
                permissions,
                file.menus(),
                file.roles(),
                file.dataResources(),
                file.realm(),
                file.dictionaries(),
                file.parameters(),
                sensitiveFields);
        try {
            manifest.validate();
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
        return manifest;
    }

    /**
     * 只读取 classpath 清单文件中的 Data Resource，供数据权限拦截器在启动时建立表与属性的映射。
     *
     * @param classLoader 加载清单文件的类加载器
     * @return 已校验的数据资源；没有清单文件时为空
     * @throws IllegalStateException 清单文件无法解析，或数据资源违反 {@link ResourceManifest#validateDataResources} 的规则
     */
    public static List<DataResource> dataResources(ClassLoader classLoader) {
        return dataResources(classLoader, MANIFEST_LOCATION);
    }

    static List<DataResource> dataResources(ClassLoader classLoader, String location) {
        List<DataResource> resources = read(classLoader, location).dataResources();
        try {
            ResourceManifest.validateDataResources(resources);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
        return resources;
    }

    private static Set<String> scan(String basePackage, ClassLoader classLoader) {
        String annotation = RequiresPermission.class.getName();
        // 只读取类文件元数据，不加载、不实例化业务类，CI 中无需启动应用。
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.setResourceLoader(new DefaultResourceLoader(classLoader));
        scanner.addIncludeFilter((reader, factory) -> {
            AnnotationMetadata metadata = reader.getAnnotationMetadata();
            return metadata.hasAnnotation(annotation) || metadata.hasAnnotatedMethods(annotation);
        });
        Set<String> codes = new HashSet<>();
        for (var candidate : scanner.findCandidateComponents(basePackage)) {
            AnnotationMetadata metadata = ((AnnotatedBeanDefinition) candidate).getMetadata();
            Map<String, Object> type = metadata.getAnnotationAttributes(annotation);
            if (type != null) codes.add((String) type.get("value"));
            for (MethodMetadata method : metadata.getAnnotatedMethods(annotation)) {
                codes.add((String) method.getAnnotationAttributes(annotation).get("value"));
            }
        }
        return codes;
    }

    private static List<SensitiveField> scanSensitiveFields(String basePackage, ClassLoader classLoader) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.setResourceLoader(new DefaultResourceLoader(classLoader));
        scanner.addIncludeFilter((reader, factory) -> true);
        List<SensitiveField> fields = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(basePackage)) {
            try {
                // 字段注解不在 Spring 的类元数据中；只加载类型，不执行静态初始化，CI 生成清单无需启动业务类。
                Class<?> type = Class.forName(candidate.getBeanClassName(), false, classLoader);
                Map<String, io.saasforge.sdk.security.SensitiveField> recordFields = new HashMap<>();
                for (var field : type.getDeclaredFields()) {
                    var annotation = field.getAnnotation(io.saasforge.sdk.security.SensitiveField.class);
                    if (annotation == null) continue;
                    fields.add(declaration(annotation));
                    if (type.isRecord()) recordFields.put(field.getName(), annotation);
                }
                for (var method : type.getDeclaredMethods()) {
                    var annotation = method.getAnnotation(io.saasforge.sdk.security.SensitiveField.class);
                    if (annotation == null) continue;
                    // record component 的同一声明同时出现在 backing field 与 accessor 上，不是两次登记。
                    if (method.getParameterCount() == 0 && annotation.equals(recordFields.get(method.getName())))
                        continue;
                    fields.add(declaration(annotation));
                }
            } catch (ClassNotFoundException | LinkageError error) {
                throw new IllegalStateException(
                        "Cannot scan Sensitive Field declarations in " + candidate.getBeanClassName(), error);
            }
        }
        return fields;
    }

    private static SensitiveField declaration(io.saasforge.sdk.security.SensitiveField annotation) {
        return new SensitiveField(
                annotation.path(),
                annotation.grade(),
                annotation.defaultRule().isEmpty() ? null : annotation.defaultRule());
    }

    private static ManifestFile read(ClassLoader classLoader, String location) {
        try (InputStream stream = classLoader.getResourceAsStream(location)) {
            if (stream == null) return new ManifestFile(null, null, null, null, null, null, null, null);
            return JSON.readValue(stream, ManifestFile.class);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid manifest file " + location, error);
        }
    }

    private record PermissionInfo(String name, String group) {}

    private record ManifestFile(
            Map<String, PermissionInfo> permissions,
            List<MenuNode> menus,
            List<RoleTemplate> roles,
            List<DataResource> dataResources,
            String realm,
            List<Dictionary> dictionaries,
            List<Parameter> parameters,
            List<SensitiveField> sensitiveFields) {
        ManifestFile {
            permissions = permissions == null ? Map.of() : permissions;
            menus = menus == null ? List.of() : menus;
            roles = roles == null ? List.of() : roles;
            dataResources = dataResources == null ? List.of() : dataResources;
            sensitiveFields = sensitiveFields == null ? List.of() : sensitiveFields;
        }
    }
}
