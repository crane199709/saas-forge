package io.saasforge.sdk.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

/** 启用统一在线通道；安全 SDK 不反向依赖 Manifest、审计或 Web SDK。 */
@AutoConfiguration(after = FieldMaskingAutoConfiguration.class)
@ConditionalOnProperty(prefix = "forge.field-masking", name = "enabled", havingValue = "true")
@ConditionalOnClass(JsonMapper.class)
public final class FieldMaskingJacksonAutoConfiguration {
    /** Jackson 自动配置收集此模块，为带注解的属性安装统一序列化器。 */
    @Bean
    JacksonModule fieldMaskingJacksonModule(ObjectProvider<FieldMaskingPolicyProvider> provider) {
        return new FieldMaskingJacksonModule(provider);
    }

    /** HTTP 响应在发送前形成完整 JSON 树；无需 Servlet 全局缓冲过滤器。 */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FieldMaskingResponseAdvice fieldMaskingResponseAdvice(JsonMapper mapper) {
        return new FieldMaskingResponseAdvice(mapper);
    }
}
