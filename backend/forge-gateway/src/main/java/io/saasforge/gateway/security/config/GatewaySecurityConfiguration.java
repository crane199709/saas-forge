package io.saasforge.gateway.security.config;

import io.saasforge.gateway.envelope.service.ErrorEnvelopeService;
import io.saasforge.gateway.security.service.MembershipStateVerifier;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.security.ForgeSecurityProperties;
import io.saasforge.sdk.security.PlatformJwtValidator;
import io.saasforge.sdk.security.TenantJwtValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// SDK 的自动配置只在 Servlet 应用生效，WebFlux 网关自行注册同一份配置。
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ForgeSecurityProperties.class)
class GatewaySecurityConfiguration {
    private static final ServerWebExchangeMatcher PLATFORM_ROUTES =
            ServerWebExchangeMatchers.pathMatchers("/platform/**");

    /**
     * 按路由强制 issuer（ADR-0003）：{@code /platform/**} 只接受 Platform Realm Token，其余受保护路由只接受 Tenant Realm Token。
     * 两个 Realm 的 issuer 与签名密钥都不同，用错 Realm 的 Token 验签失败，得到 401 而不是 403。
     */
    @Bean
    ReactiveAuthenticationManagerResolver<ServerWebExchange> realmAuthenticationManagers(
            ForgeSecurityProperties properties, MembershipStateVerifier states) {
        var tenantDecoder =
                NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        tenantDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), new TenantJwtValidator()));
        // 验签之后再比对即时失效状态：BadJwtException 由资源服务器转换为 401 auth.unauthenticated 信封（质询头为 invalid_token）。
        ReactiveJwtDecoder tenant = token -> tenantDecoder.decode(token).flatMap(states::verify);
        var platform = NimbusReactiveJwtDecoder.withJwkSetUri(
                        properties.platform().jwkSetUri())
                .build();
        platform.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.platform().issuer()), new PlatformJwtValidator()));
        ReactiveAuthenticationManager tenantManager = new JwtReactiveAuthenticationManager(tenant);
        ReactiveAuthenticationManager platformManager = new JwtReactiveAuthenticationManager(platform);
        return exchange ->
                PLATFORM_ROUTES.matches(exchange).map(match -> match.isMatch() ? platformManager : tenantManager);
    }

    /**
     * 缺失、伪造、issuer 不符、过期 Token 以及 Membership 状态不一致都在这里输出 401 信封。资源服务器与
     * {@code exceptionHandling} 各有入口，两处都要配置，否则 Bearer Token 无效时仍是空响应体。
     *
     * <p>先让 Bearer 默认处理器写出 RFC 6750 的 {@code WWW-Authenticate}，再写信封：两者结果一致，只是把空响应体换成信封。
     */
    @Bean
    SecurityWebFilterChain gatewaySecurity(
            ServerHttpSecurity http,
            ErrorEnvelopeService envelopes,
            ReactiveAuthenticationManagerResolver<ServerWebExchange> realmAuthenticationManagers) {
        var bearerEntryPoint = new BearerTokenServerAuthenticationEntryPoint();
        var bearerAccessDenied = new BearerTokenServerAccessDeniedHandler();
        ServerAuthenticationEntryPoint entryPoint = (exchange, ex) -> bearerEntryPoint
                .commence(headersOnly(exchange), ex)
                .then(Mono.defer(
                        () -> envelopes.write(exchange, FrameworkErrorCode.AUTH_UNAUTHENTICATED, ex.getMessage())));
        ServerAccessDeniedHandler accessDeniedHandler = (exchange, ex) -> bearerAccessDenied
                .handle(headersOnly(exchange), ex)
                .then(Mono.defer(() -> envelopes.write(exchange, FrameworkErrorCode.AUTH_FORBIDDEN, ex.getMessage())));
        return http.csrf(csrf -> csrf.disable())
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(auth -> auth.pathMatchers(
                                HttpMethod.POST,
                                "/iam/auth/login",
                                "/iam/auth/logout",
                                "/iam/auth/exchange",
                                "/iam/auth/refresh",
                                "/iam/auth/switch",
                                "/iam/auth/login/send-code",
                                "/iam/auth/code-login",
                                "/iam/auth/register/send-code",
                                "/iam/auth/register",
                                "/iam/platform/auth/login",
                                "/iam/platform/auth/logout",
                                "/iam/platform/auth/exchange",
                                "/iam/platform/auth/refresh")
                        .permitAll()
                        .pathMatchers(
                                HttpMethod.GET,
                                "/iam/oauth2/authorize",
                                "/iam/oauth2/jwks",
                                "/iam/.well-known/oauth-authorization-server",
                                "/iam/.well-known/openid-configuration",
                                "/iam/platform/oauth2/jwks",
                                "/iam/platform/.well-known/openid-configuration",
                                "/actuator/health",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/webjars/**",
                                "/iam/v3/api-docs/**",
                                "/demo/v3/api-docs/**",
                                "/platform/v3/api-docs/**")
                        .permitAll()
                        // 只供服务内部或服务间直连调用的 iam 端点，带有效 Token 也不经网关暴露；
                        // 匿名的授权端点与 JWKS 已在上面放行，其余 OAuth2 端点（token、introspect、revoke 等）一律拒绝。
                        .pathMatchers("/iam/oauth2/**", "/iam/internal/**")
                        .denyAll()
                        // 其余 iam 接口默认要求 Tenant Realm Token，新增接口不必再改网关；细粒度权限由 iam 判断。
                        .pathMatchers("/iam/**", "/demo/**", "/platform/**")
                        .authenticated()
                        .anyExchange()
                        .denyAll())
                .oauth2ResourceServer(server -> server.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .authenticationManagerResolver(realmAuthenticationManagers))
                .build();
    }

    /** Bearer 默认处理器写完响应头后会直接结束响应；屏蔽结束动作，响应体留给信封。 */
    private static ServerWebExchange headersOnly(ServerWebExchange exchange) {
        return exchange.mutate()
                .response(new ServerHttpResponseDecorator(exchange.getResponse()) {
                    @Override
                    public Mono<Void> setComplete() {
                        return Mono.empty();
                    }
                })
                .build();
    }
}
