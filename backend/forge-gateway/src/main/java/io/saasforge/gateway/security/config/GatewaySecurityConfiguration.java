package io.saasforge.gateway.security.config;

import io.saasforge.gateway.envelope.service.ErrorEnvelopeService;
import io.saasforge.gateway.routing.config.GatewayServicesProperties;
import io.saasforge.gateway.security.service.MembershipStateVerifier;
import io.saasforge.gateway.security.service.OperatorStateVerifier;
import io.saasforge.sdk.core.ClientIpHeader;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.security.ForgeSecurityProperties;
import io.saasforge.sdk.security.PlatformJwtValidator;
import io.saasforge.sdk.security.TenantJwtValidator;
import java.util.stream.Stream;
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
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// SDK 的自动配置只在 Servlet 应用生效，WebFlux 网关自行注册同一份配置。
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ForgeSecurityProperties.class, GatewayServicesProperties.class})
class GatewaySecurityConfiguration {
    /** 两个 Realm 均在验签后比对即时失效状态；服务的 Realm 由配置决定，IAM 平台接口保留专属规则。 */
    @Bean
    ReactiveAuthenticationManagerResolver<ServerWebExchange> realmAuthenticationManagers(
            ForgeSecurityProperties properties,
            MembershipStateVerifier states,
            OperatorStateVerifier operators,
            GatewayServicesProperties services) {
        var tenantDecoder =
                NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        tenantDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), new TenantJwtValidator()));
        // 验签之后再比对即时失效状态：BadJwtException 由资源服务器转换为 401 auth.unauthenticated 信封（质询头为 invalid_token）。
        var platformDecoder = NimbusReactiveJwtDecoder.withJwkSetUri(
                        properties.platform().jwkSetUri())
                .build();
        platformDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.platform().issuer()), new PlatformJwtValidator()));
        ReactiveJwtDecoder platform = token -> platformDecoder.decode(token).flatMap(operators::verify);
        ReactiveAuthenticationManager platformManager = new JwtReactiveAuthenticationManager(platform);
        var platformRoutes = ServerWebExchangeMatchers.pathMatchers(Stream.concat(
                        Stream.of("/iam/platform/**"),
                        services.services().stream()
                                .filter(service -> service.realm() == GatewayServicesProperties.Realm.PLATFORM)
                                .map(service -> service.prefix() + "/**"))
                .toArray(String[]::new));
        return exchange -> platformRoutes.matches(exchange).map(match -> {
            if (match.isMatch()) return platformManager;
            // ClientIpFilter 已覆盖此头；只把本次请求的可信来源绑定到认证管理器。
            String clientIp = exchange.getRequest().getHeaders().getFirst(ClientIpHeader.NAME);
            ReactiveJwtDecoder tenant =
                    token -> tenantDecoder.decode(token).flatMap(jwt -> states.verify(jwt, clientIp));
            return new JwtReactiveAuthenticationManager(tenant);
        });
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
            GatewayServicesProperties services,
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
                .authorizeExchange(auth -> {
                    auth.pathMatchers(
                                    HttpMethod.POST,
                                    "/iam/auth/login",
                                    "/iam/auth/logout",
                                    "/iam/auth/exchange",
                                    "/iam/auth/refresh",
                                    "/iam/auth/switch",
                                    "/iam/auth/memberships",
                                    "/iam/auth/login/send-code",
                                    "/iam/auth/code-login",
                                    // 待 MFA 状态保存在 iam 登录会话中，由 iam 校验
                                    "/iam/auth/login/mfa",
                                    "/iam/auth/register/send-code",
                                    "/iam/auth/register",
                                    // 忘记密码：重设凭证与 MFA 由 iam 校验
                                    "/iam/auth/password/forgot/send-code",
                                    "/iam/auth/password/forgot/verify",
                                    "/iam/auth/password/forgot/reset",
                                    "/iam/auth/invitations/preview",
                                    "/iam/auth/invitations/accept",
                                    // 绑定 Authenticator 也接受 iam 登录会话（尚未取得 Token 时当场绑定），由 iam 校验会话或 Token
                                    "/iam/auth/mfa/bind/start",
                                    "/iam/auth/mfa/bind/confirm",
                                    "/iam/platform/auth/login",
                                    // Operator 登录第二步（待 MFA、必须绑定）的状态保存在 iam 登录会话中，由 iam 校验
                                    "/iam/platform/auth/login/mfa",
                                    "/iam/platform/auth/login/bind/start",
                                    "/iam/platform/auth/login/bind/confirm",
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
                                    "/webjars/**")
                            .permitAll()
                            // 只供服务内部或服务间直连调用的 iam 端点，带有效 Token 也不经网关暴露；
                            // 匿名的授权端点与 JWKS 已在上面放行，其余 OAuth2 端点（token、introspect、revoke 等）一律拒绝。
                            .pathMatchers("/iam/oauth2/**", "/iam/platform/oauth2/**", "/iam/internal/**")
                            .denyAll();
                    for (var service : services.services()) {
                        auth.pathMatchers(service.prefix() + "/internal/**")
                                .denyAll()
                                .pathMatchers(HttpMethod.GET, service.prefix() + "/v3/api-docs/**")
                                .permitAll()
                                .pathMatchers(service.prefix() + "/**")
                                .authenticated();
                    }
                    auth.anyExchange().denyAll();
                })
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
