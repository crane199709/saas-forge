package io.saasforge.sdk.security;

import io.saasforge.sdk.core.ResourceManifest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

/** 本地敏感字段声明扫描，不连接 IAM；Manifest 与日志启动阶段复用同一注解收集规则。 */
public final class SensitiveFieldDeclarations {
    private SensitiveFieldDeclarations() {}

    /** 扫描应用包中的声明，不执行业务类静态初始化；record 传播的注解只收集一次。 */
    public static List<ResourceManifest.SensitiveField> annotated(String basePackage, ClassLoader loader) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.setResourceLoader(new DefaultResourceLoader(loader));
        scanner.addIncludeFilter((reader, factory) -> true);
        List<ResourceManifest.SensitiveField> fields = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(basePackage)) {
            try {
                Class<?> type = Class.forName(candidate.getBeanClassName(), false, loader);
                Map<String, SensitiveField> recordFields = new HashMap<>();
                for (var field : type.getDeclaredFields()) {
                    var annotation = field.getAnnotation(SensitiveField.class);
                    if (annotation == null) {
                        continue;
                    }
                    fields.add(declaration(annotation));
                    if (type.isRecord()) {
                        recordFields.put(field.getName(), annotation);
                    }
                }
                for (var method : type.getDeclaredMethods()) {
                    var annotation = method.getAnnotation(SensitiveField.class);
                    if (annotation == null) {
                        continue;
                    }
                    if (method.getParameterCount() == 0 && annotation.equals(recordFields.get(method.getName()))) {
                        continue;
                    }
                    fields.add(declaration(annotation));
                }
            } catch (ClassNotFoundException | LinkageError error) {
                throw new IllegalStateException(
                        "Cannot scan Sensitive Field declarations in " + candidate.getBeanClassName(), error);
            }
        }
        return List.copyOf(fields);
    }

    /** 读取本地 Manifest 与应用包注解，供日志在 IAM 与 Spring 容器尚未可用时登记字段名。 */
    public static List<ResourceManifest.SensitiveField> local(String basePackage, ClassLoader loader) {
        var fields = new ArrayList<>(annotated(basePackage, loader));
        try (var stream = loader.getResourceAsStream("META-INF/forge/manifest.json")) {
            if (stream != null) {
                var json = JsonMapper.builder().build().readTree(stream);
                for (var node : json.path("sensitiveFields")) {
                    fields.add(new ResourceManifest.SensitiveField(
                            node.path("path").asString(), node.path("grade").asString(), null));
                }
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot read local Sensitive Field declarations", error);
        }
        return List.copyOf(fields);
    }

    private static ResourceManifest.SensitiveField declaration(SensitiveField field) {
        return new ResourceManifest.SensitiveField(
                field.path(), field.grade(), field.defaultRule().isEmpty() ? null : field.defaultRule());
    }
}
