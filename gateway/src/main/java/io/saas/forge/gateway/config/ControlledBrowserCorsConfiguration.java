package io.saas.forge.gateway.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods = false)
class ControlledBrowserCorsConfiguration {

    @Bean
    FilterRegistrationBean<CorsFilter> controlledBrowserCors(
            @Value("${browser.rootDomain}") String rootDomain,
            GatewayProblemDetailsWriter problemDetailsWriter) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(
                "https://platform." + rootDomain,
                "https://console." + rootDomain));
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Idempotency-Key", "X-SF-CSRF", "traceparent", "tracestate"));
        cors.setExposedHeaders(List.of("Location", "Retry-After"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        CorsConfiguration discoveryCors = new CorsConfiguration(cors);
        discoveryCors.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
        discoveryCors.setAllowCredentials(false);
        source.registerCorsConfiguration("/.well-known/jwks.json", discoveryCors);
        CorsFilter filter = new CorsFilter(source);
        DefaultCorsProcessor processor = new DefaultCorsProcessor();
        filter.setCorsProcessor((configuration, request, response) -> {
            boolean accepted = processor.processRequest(configuration, request, response);
            if (!accepted) {
                // 外层错误规范化 Filter 已缓存响应；将框架纯文本拒绝标为 Gateway Problem，避免误报上游 502。
                response.resetBuffer();
                problemDetailsWriter.write(request, response, HttpStatus.FORBIDDEN, "BROWSER_REQUEST_REJECTED",
                        "The browser Origin, method or request headers are not allowed.");
            }
            return accepted;
        });
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }
}
