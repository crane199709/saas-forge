package io.saasforge.gateway.routing.config;

import java.util.LinkedHashSet;
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class GatewayOpenApiConfiguration {
    GatewayOpenApiConfiguration(GatewayServicesProperties services, SwaggerUiConfigProperties swagger) {
        var urls = new LinkedHashSet<SwaggerUrl>();
        for (var service : services.services()) {
            String name = service.prefix().substring(1);
            urls.add(new SwaggerUrl(name, service.prefix() + "/v3/api-docs", name));
        }
        swagger.setUrls(urls);
    }
}
