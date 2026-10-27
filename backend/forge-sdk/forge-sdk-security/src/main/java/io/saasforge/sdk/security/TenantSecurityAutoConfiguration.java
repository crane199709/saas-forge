package io.saasforge.sdk.security;

import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/** 业务服务引入 starter 即获得 Tenant Realm 验签和请求上下文。 */
@AutoConfiguration(
        beforeName = {
            "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration"
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class TenantSecurityAutoConfiguration {
    /** 使用 JWKS 缓存公钥在本地验签，同时固定可信 issuer，不根据 Token 动态发现服务。 */
    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder tenantJwtDecoder(
            @Value("${forge.security.issuer:http://localhost:8080/iam}") String issuer,
            @Value("${forge.security.jwk-set-uri:http://localhost:8101/oauth2/jwks}") String jwks) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new TenantJwtValidator()));
        return decoder;
    }

    /**
     * 401 输出 {@code auth.unauthenticated} 信封。
     *
     * @param tracer 用于填入 traceId
     * @return 认证入口，应用自定义同类型 Bean 时让位
     */
    @Bean
    @ConditionalOnMissingBean
    public EnvelopeAuthenticationEntryPoint forgeAuthenticationEntryPoint(ObjectProvider<Tracer> tracer) {
        return new EnvelopeAuthenticationEntryPoint(tracer);
    }

    /**
     * 403 输出 {@code auth.forbidden} 信封。
     *
     * @param tracer 用于填入 traceId
     * @return 拒绝处理器，应用自定义同类型 Bean 时让位
     */
    @Bean
    @ConditionalOnMissingBean
    public EnvelopeAccessDeniedHandler forgeAccessDeniedHandler(ObjectProvider<Tracer> tracer) {
        return new EnvelopeAccessDeniedHandler(tracer);
    }

    /**
     * 健康与 OpenAPI 允许匿名读取，业务接口必须携带 JWT；应用自定义过滤链时须自行加入 {@link CurrentTenantFilter} 与上面两个信封处理器。
     *
     * <p>资源服务器对 Bearer Token 有自己的入口与拒绝处理器，只配 {@code exceptionHandling} 时无效 Token 仍返回空响应体，所以两处都要配置。
     */
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain tenantSecurity(
            HttpSecurity http,
            EnvelopeAuthenticationEntryPoint entryPoint,
            EnvelopeAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(server -> server.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(Customizer.withDefaults()))
                .addFilterAfter(new CurrentTenantFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }

    /** 服务使用 Spring MVC 时，让方法安全的拒绝绕过服务的兜底异常处理。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    static class MethodSecurityExceptionConfiguration {

        @Bean
        SecurityExceptionPassthroughAdvice forgeSecurityExceptionPassthroughAdvice() {
            return new SecurityExceptionPassthroughAdvice();
        }
    }
}
