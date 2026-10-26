package io.saasforge.sdk.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
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

    /** 健康与 OpenAPI 允许匿名读取，业务接口必须携带 JWT；应用自定义过滤链时须自行加入 {@link CurrentTenantFilter}。 */
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain tenantSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .addFilterAfter(new CurrentTenantFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
