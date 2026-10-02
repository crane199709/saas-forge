package io.saas.forge.gateway.config;

import io.opentelemetry.api.OpenTelemetry;
import io.saas.forge.observability.HttpTraceFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class GatewayTracingConfiguration {
    @Bean
    FilterRegistrationBean<HttpTraceFilter> gatewayTrace(OpenTelemetry telemetry, GatewayRouteCatalog catalog) {
        var filter = new HttpTraceFilter(telemetry, request -> catalog.matching(request.getRequestURI()).stream()
                .filter(route -> route.method().matches(request.getMethod())).map(GatewayRouteCatalog.Route::path)
                .findFirst().orElse("/unmatched"));
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
