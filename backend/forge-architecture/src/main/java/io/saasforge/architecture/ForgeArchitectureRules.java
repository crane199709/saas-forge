package io.saasforge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

/** 各模块共享的生产字节码约束，测试样例不参与生产扫描。 */
public final class ForgeArchitectureRules {
    private static final String SDK = "io.saasforge.sdk.";
    private static final Set<String> SQL_ANNOTATIONS = Set.of(
            "Select",
            "Insert",
            "Update",
            "Delete",
            "SelectProvider",
            "InsertProvider",
            "UpdateProvider",
            "DeleteProvider");
    private static final Set<String> FRAGMENTS =
            Set.of("apply", "last", "inSql", "notInSql", "exists", "notExists", "having", "setSql");
    private static final Set<String> LAYERS =
            Set.of("controller", "service", "mapper", "entity", "model", "config", "filter");
    private static final Set<String> JDBC = Set.of(
            "java.sql.Connection",
            "java.sql.Driver",
            "java.sql.Statement",
            "java.sql.PreparedStatement",
            "java.sql.CallableStatement",
            "java.sql.DriverManager",
            "javax.sql.DataSource",
            "org.springframework.jdbc.core.JdbcTemplate",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "org.springframework.jdbc.core.JdbcOperations",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations");

    private ForgeArchitectureRules() {}

    /** SQL 注解、字符串 Wrapper 和 SQL 片段在 SDK 中也不豁免。 */
    public static ArchRule persistence() {
        return persistence(Set.of());
    }

    /**
     * 同 {@link #persistence()}，但允许指定类（含其内部类）依赖 JDBC，用于连接不属于平台的外部数据库；
     * 这些类的 SQL 注解、字符串 Wrapper 与 SQL 片段限制不变。
     *
     * @param externalJdbc 豁免 JDBC 依赖的类全限定名
     */
    public static ArchRule persistence(Set<String> externalJdbc) {
        return classes().should(new ArchCondition<>("遵守 SQL 与持久层规范") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                type.getMethods()
                        .forEach(method -> method.getAnnotations().forEach(annotation -> {
                            String name = annotation.getRawType().getName();
                            if (name.startsWith("org.apache.ibatis.annotations.")
                                    && SQL_ANNOTATIONS.contains(
                                            name.substring("org.apache.ibatis.annotations.".length())
                                                    .replace("$List", ""))) {
                                reject(events, type, method.getFullName() + " 使用 SQL 注解 " + name);
                            }
                        }));
                type.getDirectDependenciesFromSelf().forEach(dependency -> {
                    JavaClass target = dependency.getTargetClass();
                    if (target.isAssignableTo("com.baomidou.mybatisplus.core.conditions.query.QueryWrapper")
                            || target.isAssignableTo("com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper")
                            || target.isAssignableTo(
                                    "com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper")
                            || target.isAssignableTo(
                                    "com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper")) {
                        reject(events, type, dependency.getDescription());
                    }
                    if (!type.getPackageName().startsWith(SDK)
                            && !externalJdbc(type, externalJdbc)
                            && JDBC.stream().anyMatch(target::isAssignableTo)) {
                        reject(events, type, dependency.getDescription());
                    }
                });
                type.getCodeUnitAccessesFromSelf().forEach(call -> {
                    String owner = call.getTargetOwner().getName();
                    if ((owner.startsWith("com.baomidou.mybatisplus.")
                                    || call.getTargetOwner()
                                            .isAssignableTo("com.baomidou.mybatisplus.core.conditions.Wrapper"))
                            && (FRAGMENTS.contains(call.getName())
                                    || (call.getName().equals("select")
                                            && (call.getTargetOwner()
                                                            .isAssignableTo(
                                                                    "com.baomidou.mybatisplus.core.conditions.query.QueryWrapper")
                                                    || call.getTargetOwner()
                                                            .isAssignableTo(
                                                                    "com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper")
                                                    || call.getTarget().getRawParameterTypes().stream()
                                                            .anyMatch(t ->
                                                                    t.getName().equals("[Ljava.lang.String;")
                                                                            || t.getName()
                                                                                    .equals("java.lang.String[]")))))) {
                        reject(events, type, "SQL 片段: " + call.getDescription());
                    }
                });
            }
        });
    }

    /** 服务通过 SDK 声明任务，避免绕开分布式互斥与租户上下文。 */
    public static ArchRule scheduling() {
        return classes().should(new ArchCondition<>("SDK 之外不使用 Spring 原生定时任务") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                if (type.getPackageName().startsWith(SDK)) return;
                for (String annotation : Set.of(
                        "org.springframework.scheduling.annotation.EnableScheduling",
                        "org.springframework.scheduling.annotation.Scheduled",
                        "org.springframework.scheduling.annotation.Schedules")) {
                    if (type.isAnnotatedWith(annotation) || type.isMetaAnnotatedWith(annotation)) {
                        reject(events, type, type.getName() + " 使用 Spring 原生调度注解 " + annotation);
                    }
                    type.getMethods().stream()
                            .filter(method ->
                                    method.isAnnotatedWith(annotation) || method.isMetaAnnotatedWith(annotation))
                            .forEach(method ->
                                    reject(events, type, method.getFullName() + " 使用 Spring 原生调度注解 " + annotation));
                }
            }
        });
    }

    /** SDK 不能反向依赖服务，core 不能依赖其他能力包。 */
    public static ArchRule sdkDependencies() {
        return classes().should(new ArchCondition<>("保持 SDK 依赖方向") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                if (!type.getPackageName().startsWith(SDK)) return;
                type.getDirectDependenciesFromSelf().forEach(dependency -> {
                    String target = dependency.getTargetClass().getPackageName();
                    if ((target.startsWith("io.saasforge.") && !target.startsWith(SDK))
                            || ((type.getPackageName().equals(SDK + "core")
                                            || type.getPackageName().startsWith(SDK + "core."))
                                    && target.startsWith(SDK)
                                    && !(target.equals(SDK + "core") || target.startsWith(SDK + "core.")))) {
                        reject(events, type, dependency.getDescription());
                    }
                });
            }
        });
    }

    /** 检查精确包之间的循环，包括同一个 SDK 模块内的子包。 */
    public static ArchRule sdkCycles() {
        return slices().matching("io.saasforge.sdk.(**)")
                .should()
                .beFreeOfCycles()
                .allowEmptyShould(true);
    }

    /** 服务以功能包分层，跨功能访问数据必须经过 service。 */
    public static ArchRule serviceStructure(String root) {
        return classes().should(new ArchCondition<>("遵守 " + root + " 功能包分层") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                String[] source = location(type, root);
                if (source == null) return;
                if (source.length != 2 || !LAYERS.contains(source[1])) {
                    if (!(type.getPackageName().equals(root)
                            && type.isAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication"))) {
                        reject(events, type, type.getName() + " 不在功能包的白名单层中");
                    }
                    return;
                }
                type.getDirectDependenciesFromSelf().forEach(dependency -> {
                    String[] target = location(dependency.getTargetClass(), root);
                    if (target == null || target.length != 2) return;
                    boolean otherFeature = !source[0].equals(target[0]);
                    if ((otherFeature && target[1].equals("mapper"))
                            || (source[1].equals("controller")
                                    && (target[1].equals("mapper") || (target[1].equals("service") && otherFeature)))) {
                        reject(events, type, dependency.getDescription());
                    }
                });
            }
        });
    }

    /** 功能包循环包含所有层的依赖。 */
    public static ArchRule featureCycles(String root) {
        return slices().matching(root + ".(*)..").should().beFreeOfCycles().allowEmptyShould(true);
    }

    /** IAM 的全局表不经过 tenantLine 拦截器。 */
    public static ArchRule iamMappers() {
        return classes().should(new ArchCondition<>("IAM Mapper 显式忽略 tenantLine") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                if (!type.getPackageName().startsWith("io.saasforge.iam.")
                        || !type.isAnnotatedWith("org.apache.ibatis.annotations.Mapper")) return;
                var annotation = type.tryGetAnnotationOfType("com.baomidou.mybatisplus.annotation.InterceptorIgnore");
                if (annotation.isEmpty()
                        || !annotation.get().get("tenantLine").orElse("").equals("true")) {
                    reject(events, type, type.getName() + " 缺少 @InterceptorIgnore(tenantLine = true)");
                }
            }
        });
    }

    /** REST 接口显式声明信封，普通 public 辅助方法不是 HTTP 接口。 */
    public static ArchRule apiResponses() {
        return classes().should(new ArchCondition<>("REST 接口返回 ApiResponse") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                if (!type.getPackageName().contains(".controller")
                        || !type.isAnnotatedWith("org.springframework.web.bind.annotation.RestController")) return;
                type.getAllMethods().stream()
                        .filter(m -> m.getModifiers().contains(JavaModifier.PUBLIC))
                        .filter(m -> m.isAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")
                                || m.isMetaAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping"))
                        .filter(m -> !m.getRawReturnType().getName().equals("io.saasforge.sdk.core.api.ApiResponse"))
                        .forEach(m -> reject(events, type, m.getFullName() + " 未返回 ApiResponse"));
            }
        });
    }

    // 按名称而非包匹配，免得 JDBC 用法扩散到同包其他类；内部类与 lambda 所在类随外部类豁免
    private static boolean externalJdbc(JavaClass type, Set<String> names) {
        String name = type.getName();
        return names.stream().anyMatch(exempt -> name.equals(exempt) || name.startsWith(exempt + "$"));
    }

    private static String[] location(JavaClass type, String root) {
        if (type.getPackageName().equals(root)) return new String[0];
        return type.getPackageName().startsWith(root + ".")
                ? type.getPackageName().substring(root.length() + 1).split("[.]")
                : null;
    }

    private static void reject(ConditionEvents events, JavaClass type, String message) {
        events.add(SimpleConditionEvent.violated(type, message));
    }
}
