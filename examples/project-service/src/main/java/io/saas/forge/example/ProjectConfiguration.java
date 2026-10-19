package io.saas.forge.example;

import io.opentelemetry.api.OpenTelemetry;
import io.saas.forge.contracts.route.HttpRouteCatalog;
import io.saas.forge.observability.HttpTraceFilter;
import io.saas.forge.sdk.permission.HttpPermissionChecker;
import io.saas.forge.sdk.permission.PermissionChecker;
import io.saas.forge.starter.security.PermissionMvcInterceptor;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.util.AntPathMatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class ProjectConfiguration {
    @Bean
    PermissionChecker projectPermissionChecker(LoadBalancerClient discovery) {
        return new HttpPermissionChecker(() -> {
            var instance = discovery.choose("gateway");
            return instance == null ? null : instance.getUri();
        });
    }

    @Bean
    WebMvcConfigurer projectPermissionInterception(PermissionChecker checker) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new PermissionMvcInterceptor(checker))
                        .addPathPatterns("/api/v1/projects", "/api/v1/projects/**");
            }
        };
    }
    @Bean
    org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer strictTextFields() {
        return builder -> builder.withCoercionConfig(tools.jackson.databind.type.LogicalType.Textual, config -> {
            config.setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Integer, tools.jackson.databind.cfg.CoercionAction.Fail);
            config.setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Float, tools.jackson.databind.cfg.CoercionAction.Fail);
            config.setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Boolean, tools.jackson.databind.cfg.CoercionAction.Fail);
        });
    }

    @Bean
    FilterRegistrationBean<HttpTraceFilter> projectTrace(
            OpenTelemetry telemetry, HttpRouteCatalog catalog) {
        var matcher = new AntPathMatcher();
        var routes = catalog.routes().stream().filter(route -> route.serviceId().equals("project-service")).toList();
        var filter = new HttpTraceFilter(telemetry, request -> routes.stream()
                .filter(route -> route.method().name().equals(request.getMethod())
                        && matcher.match(route.path(), request.getRequestURI()))
                .map(HttpRouteCatalog.Route::path).findFirst().orElse("/unmatched"));
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
