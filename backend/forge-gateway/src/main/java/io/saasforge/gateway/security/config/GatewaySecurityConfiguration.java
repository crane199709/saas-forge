package io.saasforge.gateway.security.config;

import io.saasforge.gateway.security.service.MembershipStateVerifier;
import io.saasforge.sdk.security.TenantJwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration(proxyBeanMethods = false)
class GatewaySecurityConfiguration {
    @Bean
    ReactiveJwtDecoder tenantDecoder(
            @Value("${forge.security.issuer:http://localhost:8080/iam}") String issuer,
            @Value("${forge.security.jwk-set-uri:http://localhost:8101/oauth2/jwks}") String jwks,
            MembershipStateVerifier states) {
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new TenantJwtValidator()));
        // 验签之后再比对即时失效状态：BadJwtException 由资源服务器转换为 401 invalid_token。
        return token -> decoder.decode(token).flatMap(states::verify);
    }

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http) {
        return http.csrf(csrf -> csrf.disable())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(auth -> auth.pathMatchers(
                                HttpMethod.POST,
                                "/iam/auth/login",
                                "/iam/auth/logout",
                                "/iam/auth/exchange",
                                "/iam/auth/refresh",
                                "/iam/auth/switch")
                        .permitAll()
                        .pathMatchers(
                                HttpMethod.GET,
                                "/iam/oauth2/authorize",
                                "/iam/oauth2/jwks",
                                "/iam/.well-known/oauth-authorization-server",
                                "/actuator/health",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/webjars/**",
                                "/iam/v3/api-docs/**",
                                "/demo/v3/api-docs/**",
                                "/platform/v3/api-docs/**")
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, "/iam/auth/memberships")
                        .authenticated()
                        .pathMatchers("/demo/**")
                        .authenticated()
                        // Platform Realm 由工单 08 接入，Tenant Token 不能访问平台路由。
                        .anyExchange()
                        .denyAll())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .build();
    }
}
