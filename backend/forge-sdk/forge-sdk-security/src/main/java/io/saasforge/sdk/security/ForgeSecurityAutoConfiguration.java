package io.saasforge.sdk.security;

import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.core.CurrentActorProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

/**
 * 业务服务引入 starter 即获得 JWT 验签和请求上下文。服务只接受一个 Realm 的 Token（ADR-0003）：默认 Tenant Realm，
 * {@code forge.security.realm=platform} 时改为 Platform Realm，此时没有租户上下文，用 {@link OperatorContext} 读取当前 Operator。
 */
@AutoConfiguration(
        beforeName = {
            "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration"
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ForgeSecurityProperties.class)
public final class ForgeSecurityAutoConfiguration {
    /**
     * 使用 JWKS 缓存公钥在本地验签，同时固定可信 issuer，不根据 Token 动态发现服务。
     *
     * <p>{@code forge.security.realm} 决定使用哪个 Realm 的 issuer、JWKS 与 claim 校验，解码器只接受该 Realm 的 Token。
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder forgeJwtDecoder(ForgeSecurityProperties properties) {
        boolean platform = isPlatform(properties.realm());
        var decoder = NimbusJwtDecoder.withJwkSetUri(
                        platform ? properties.platform().jwkSetUri() : properties.jwkSetUri())
                .build();
        decoder.setJwtValidator(
                platform
                        ? new DelegatingOAuth2TokenValidator<>(
                                JwtValidators.createDefaultWithIssuer(
                                        properties.platform().issuer()),
                                new PlatformJwtValidator())
                        : new DelegatingOAuth2TokenValidator<>(
                                JwtValidators.createDefaultWithIssuer(properties.issuer()), new TenantJwtValidator()));
        return decoder;
    }

    /**
     * 为标准列填充提供操作人。
     *
     * @return 读取当前 JWT {@code sub} 的实现，应用自定义同类型 Bean 时让位
     */
    @Bean
    @ConditionalOnMissingBean
    public CurrentActorProvider forgeCurrentActorProvider() {
        return new JwtCurrentActorProvider();
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
     * 健康与 OpenAPI 允许匿名读取，业务接口必须携带 JWT；应用自定义过滤链时须自行加入 {@link CurrentTenantFilter}（Tenant Realm）与上面两个信封处理器。
     *
     * <p>资源服务器对 Bearer Token 有自己的入口与拒绝处理器，只配 {@code exceptionHandling} 时无效 Token 仍返回空响应体，所以两处都要配置。
     */
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain forgeSecurity(
            HttpSecurity http,
            ForgeSecurityProperties properties,
            EnvelopeAuthenticationEntryPoint entryPoint,
            EnvelopeAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        // Platform Realm 的 Token 没有租户，不绑定 CurrentTenant。
        if (!isPlatform(properties.realm())) {
            http.addFilterAfter(new CurrentTenantFilter(), BearerTokenAuthenticationFilter.class);
        }
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
                .build();
    }

    private static boolean isPlatform(String realm) {
        return switch (realm) {
            case "tenant" -> false;
            case "platform" -> true;
            // 拼错的取值不能静默退回某个 Realm，否则服务会接受错误 Realm 的 Token。
            default -> throw new IllegalStateException("forge.security.realm must be tenant or platform: " + realm);
        };
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
