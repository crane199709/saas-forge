package io.saasforge.sdk.openapi;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 为真实网关探测提供服务身份，避免把其他服务或 SPA 的成功响应当作本服务。 */
@AutoConfiguration
public final class ForgeOpenApiAutoConfiguration {
    /** 在标准 OpenAPI 文档中标记本进程 spring.application.name。 */
    @Bean
    public OpenApiCustomizer forgeServiceIdentity(Environment environment) {
        String service = environment.getRequiredProperty("spring.application.name");
        return openApi -> openApi.addExtension("x-forge-service", service);
    }
}
