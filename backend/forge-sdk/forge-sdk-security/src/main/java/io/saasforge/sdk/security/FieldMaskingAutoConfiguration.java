package io.saasforge.sdk.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 显式启用 {@code forge.field-masking.enabled=true} 后才绑定必配项，兼容没有敏感字段的旧服务。 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "forge.field-masking", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(FieldMaskingProperties.class)
public final class FieldMaskingAutoConfiguration {
    /** 创建没有跨请求缓存的策略提供方，平台与租户配置提交后下次读取即生效。 */
    @Bean
    @ConditionalOnMissingBean(FieldMaskingPolicyProvider.class)
    public FieldMaskingPolicyProvider fieldMaskingPolicyProvider(FieldMaskingProperties properties) {
        return new IamFieldMaskingPolicyProvider(properties);
    }
}
