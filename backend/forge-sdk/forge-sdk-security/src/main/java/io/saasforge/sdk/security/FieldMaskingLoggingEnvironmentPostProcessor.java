package io.saasforge.sdk.security;

import ch.qos.logback.classic.PatternLayout;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ClassUtils;

/** 在 Boot 初始化日志前登记字段与 converter，启动窗口也不能回退输出敏感原值。 */
public final class FieldMaskingLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!ClassUtils.isPresent("ch.qos.logback.classic.PatternLayout", application.getClassLoader())) {
            return;
        }
        Class<?> main = application.getMainApplicationClass();
        if (main != null) {
            Map<String, String> names = new HashMap<>();
            for (var field : SensitiveFieldDeclarations.local(main.getPackageName(), application.getClassLoader())) {
                names.put(field.path(), field.path());
                String alias = field.path().substring(field.path().lastIndexOf('.') + 1);
                names.merge(alias, field.path(), (first, second) -> first.equals(second) ? first : "");
            }
            FieldMaskingLogRuntime.declarations(names);
        }
        registerConverters();
    }

    private static void registerConverters() {
        var converters = PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP;
        converters.put("m", FieldMaskingMessageConverter::new);
        converters.put("msg", FieldMaskingMessageConverter::new);
        converters.put("message", FieldMaskingMessageConverter::new);
        converters.put("kvp", FieldMaskingKeyValueConverter::new);
    }

    /** 在日志初始化之前运行，且不依赖外部配置、IAM 或 Bean 创建顺序。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 11;
    }
}
