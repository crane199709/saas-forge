package io.saasforge.sdk.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/** 连接已配置的策略端口与日志适配；未就绪时早期 converter 始终使用安全占位。 */
@AutoConfiguration(after = FieldMaskingAutoConfiguration.class)
@ConditionalOnClass(name = "ch.qos.logback.classic.PatternLayout")
@ConditionalOnBean(FieldMaskingPolicyProvider.class)
public final class FieldMaskingLoggingAutoConfiguration {
    @Bean
    FieldMaskingLogRuntime fieldMaskingLogRuntime(FieldMaskingPolicyProvider provider) {
        return new FieldMaskingLogRuntime(provider);
    }
}
