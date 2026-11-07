package io.saasforge.sdk.ops;

import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.security.EnvelopeAccessDeniedHandler;
import io.saasforge.sdk.security.EnvelopeAuthenticationEntryPoint;
import io.saasforge.sdk.security.ForgeSecurityAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/** 为 /internal/ops/** 提供独立于服务 Realm 的运维认证，网关始终拒绝这些路径。 */
@AutoConfiguration(after = ForgeSecurityAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(OpsProperties.class)
public final class OpsAutoConfiguration {
    // 在默认 JWT 链注册后再增加专用链，避免触发默认链的 ConditionalOnMissingBean 退让。
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain forgeOpsSecurity(HttpSecurity http, OpsProperties properties, ObjectProvider<Tracer> tracer)
            throws Exception {
        var entry = new EnvelopeAuthenticationEntryPoint(tracer);
        var denied = new EnvelopeAccessDeniedHandler(tracer);
        return http.securityMatcher("/internal/ops", "/internal/ops/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new OpsTokenFilter(properties), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(entry).accessDeniedHandler(denied))
                .build();
    }

    @Bean
    OpsController forgeOpsController() {
        return new OpsController();
    }
}
