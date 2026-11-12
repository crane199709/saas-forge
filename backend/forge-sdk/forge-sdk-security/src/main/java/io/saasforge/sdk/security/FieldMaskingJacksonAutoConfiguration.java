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

    /** 仅为选中的 JSON 响应启用原子缓冲，不预先改变业务对象。 */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FieldMaskingResponseAdvice fieldMaskingResponseAdvice() {
        return new FieldMaskingResponseAdvice();
    }

    /** 默认透传响应，JSON advice 选中后才缓冲真实转换器输出。 */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FieldMaskingResponseFilter fieldMaskingResponseFilter() {
        return new FieldMaskingResponseFilter();
    }
}
