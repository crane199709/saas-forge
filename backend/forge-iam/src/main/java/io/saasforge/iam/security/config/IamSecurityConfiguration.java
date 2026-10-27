package io.saasforge.iam.security.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.saasforge.iam.identity.model.ActiveMembership;
import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.membership.service.MembershipStateService;
import io.saasforge.iam.operator.model.ActiveOperator;
import io.saasforge.iam.operator.service.OperatorService;
import io.saasforge.iam.permission.config.ManifestProperties;
import io.saasforge.iam.permission.filter.ServiceTokenFilter;
import io.saasforge.iam.security.filter.EnvelopeSecurityErrorHandler;
import io.saasforge.iam.security.filter.TokenGrantLockFilter;
import io.saasforge.iam.security.service.RedisAuthorizationService;
import io.saasforge.iam.tenant.service.TenantStateService;
import io.saasforge.sdk.web.EnvelopeErrorWriter;
import jakarta.servlet.DispatcherType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
class IamSecurityConfiguration {
    private static final String PLATFORM_CLIENT = "platform-console";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(RealmIssuerProperties issuers) {
        return AuthorizationServerSettings.builder().issuer(issuers.issuer()).build();
    }

    @Bean
    RegisteredClientRepository registeredClients(ClientCredentials credentials, IamProperties properties) {
        // 每个 Realm 一个客户端：tenant-app 签发 Tenant Realm Token，platform-console 签发 Platform Realm Token。
        return new InMemoryRegisteredClientRepository(
                shellClient("tenant-app", "tenant", credentials, properties.redirectUri()),
                shellClient(
                        PLATFORM_CLIENT,
                        "platform",
                        credentials,
                        properties.platform().redirectUri()));
    }

    private static RegisteredClient shellClient(
            String clientId, String scope, ClientCredentials credentials, String redirectUri) {
        // 凭证仅由同进程的 Token 交换适配器持有；浏览器仍必须提供 PKCE 校验值。
        return RegisteredClient.withId(clientId)
                .clientId(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientSecret(new BCryptPasswordEncoder().encode(credentials.secret()))
                .redirectUri(redirectUri)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope(scope)
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false)
                        .build())
                .build();
    }

    /** Tenant Realm 的签名密钥，由标准 {@code /oauth2/jwks} 发布。 */
    @Bean
    JWKSource<SecurityContext> jwkSource(IamProperties properties) throws Exception {
        JWKSet set = new JWKSet(loadOrCreateKey(properties.signingKeyFile()));
        return (selector, context) -> selector.select(set);
    }

    /** Platform Realm 使用独立密钥，不作为 JWKSource Bean，避免被标准 {@code /oauth2/jwks} 一并发布。 */
    @Bean
    PlatformSigningKey platformSigningKey(IamProperties properties) throws Exception {
        return new PlatformSigningKey(loadOrCreateKey(properties.platform().signingKeyFile()));
    }

    /** 按 Token 定制器写入的 issuer 选择签名密钥：一个 Realm 的 Token 无法通过另一个 Realm 的 JWKS 验签。 */
    @Bean
    JwtEncoder realmJwtEncoder(
            JWKSource<SecurityContext> jwkSource, PlatformSigningKey platformKey, RealmIssuerProperties issuers) {
        String tenantIssuer = issuers.issuer();
        String platformIssuer = issuers.platform().issuer();
        // issuer 相同时无法按 issuer 区分 Realm，所有 Token 都会用 Platform 密钥签名，必须拒绝启动。
        if (platformIssuer.equals(tenantIssuer)) {
            throw new IllegalStateException("forge.security.platform.issuer must differ from forge.security.issuer");
        }
        var tenant = new NimbusJwtEncoder(jwkSource);
        var platform = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(platformKey.key())));
        return parameters -> platformIssuer.equals(parameters.getClaims().getClaimAsString(JwtClaimNames.ISS))
                ? platform.encode(parameters)
                : tenant.encode(parameters);
    }

    private static RSAKey loadOrCreateKey(String filename) throws Exception {
        Path path = Path.of(filename);
        if (!Files.exists(path)) {
            Files.createDirectories(path.toAbsolutePath().getParent());
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            var key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
            // CREATE_NEW 避免覆盖已有签名密钥，创建时即限制文件权限。
            try (var channel = Files.newByteChannel(
                    path,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                channel.write(ByteBuffer.wrap(key.toJSONString().getBytes(StandardCharsets.UTF_8)));
            }
        }
        RSAKey key = RSAKey.parse(Files.readString(path));
        if (!key.isPrivate()) {
            throw new IllegalStateException("Signing key must contain a private key");
        }
        return key;
    }

    @Bean
    ClientCredentials clientCredentials(IamProperties properties) {
        String secret = properties.clientSecret();
        return new ClientCredentials(secret.isBlank() ? UUID.randomUUID().toString() : secret);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> realmClaims(
            IdentityService identities,
            MembershipStateService membershipStates,
            TenantStateService tenantStates,
            OperatorService operators,
            RealmIssuerProperties issuers,
            IamProperties properties) {
        String platformIssuer = issuers.platform().issuer();
        boolean mfaRequired = properties.platform().mfaRequired();
        return context -> {
            // 两个 Realm 共用授权端点与登录会话：客户端决定 Realm，会话主体必须属于同一 Realm，否则拒绝签发。
            if (PLATFORM_CLIENT.equals(context.getRegisteredClient().getClientId())) {
                // MFA 尚未实现：开启强制 MFA 后，已有的登录会话与 Refresh Token 也不能再换发 Token。
                if (mfaRequired || !hasAuthority(context.getPrincipal(), "OPERATOR")) {
                    throw new OAuth2AuthenticationException("invalid_grant");
                }
                // 签发与刷新都重新确认 Operator 仍为 active。
                ActiveOperator operator = operators.findActive(
                        UUID.fromString(context.getPrincipal().getName()));
                if (operator == null) throw new OAuth2AuthenticationException("invalid_grant");
                context.getClaims()
                        .issuer(platformIssuer)
                        .subject(operator.operatorId().toString());
                return;
            }
            if (!hasAuthority(context.getPrincipal(), "TENANT")) {
                throw new OAuth2AuthenticationException("invalid_grant");
            }
            UUID identityId = UUID.fromString(context.getPrincipal().getName());
            ActiveMembership membership;
            Object requested =
                    context.getAuthorizationGrant() instanceof OAuth2AuthorizationGrantAuthenticationToken grant
                            ? grant.getAdditionalParameters().get(TenantTokenParameters.MEMBERSHIP_ID)
                            : null;
            var authorization = context.getAuthorization();
            if (requested != null) {
                // 选择或切换租户：目标只能是该身份自己的有效 Membership（ADR-0002）。
                membership = parseMembershipId(requested)
                        .flatMap(membershipId -> identities.findActiveMembership(identityId, membershipId))
                        .orElseThrow(() ->
                                new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED)));
            } else if (authorization != null && authorization.getAccessToken() != null) {
                // 普通刷新不能隐式换租户，只重新校验原 Membership。
                var claims = authorization.getAccessToken().getClaims();
                membership = identities.findForRefresh(
                        identityId, UUID.fromString((String) claims.get("membership_id")), UUID.fromString((String)
                                claims.get("tenant_id")));
            } else {
                var matches = identities.findActiveMemberships(identityId);
                membership = matches.size() == 1 ? matches.get(0) : null;
            }
            if (membership == null) throw new OAuth2AuthenticationException("invalid_grant");
            // 网关只认 Redis 中的状态，缺失即拒绝；每次签发都按数据库补齐，被淘汰的状态随刷新恢复（ADR-0005）。
            membershipStates.publish(membership.tenantId(), membership.membershipId(), membership.version());
            tenantStates.publishActive(membership.tenantId());
            context.getClaims()
                    .subject(membership.identityId().toString())
                    .claim("tenant_id", membership.tenantId().toString())
                    .claim("membership_id", membership.membershipId().toString())
                    .claim("ver", membership.version());
        };
    }

    private static boolean hasAuthority(Authentication principal, String authority) {
        return principal.getAuthorities().stream().anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    private static Optional<UUID> parseMembershipId(Object value) {
        try {
            return Optional.of(UUID.fromString(value.toString()));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    @Bean
    JwtDecoder accessTokenDecoder(JWKSource<SecurityContext> jwkSource, RealmIssuerProperties issuers) {
        var decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
        // 与下游一致：只接受本 Realm 签发给 tenant-app 的 Access Token。
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuers.issuer()),
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD, audience -> audience != null && audience.contains("tenant-app")),
                new JwtClaimValidator<String>("membership_id", Objects::nonNull)));
        return decoder;
    }

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServer(
            HttpSecurity http, StringRedisTemplate redis, RedisAuthorizationService authorizations) throws Exception {
        http.oauth2AuthorizationServer(server -> http.securityMatcher(server.getEndpointsMatcher()));
        http.addFilterBefore(
                new TokenGrantLockFilter(redis, authorizations), UsernamePasswordAuthenticationFilter.class);
        http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
    }

    @Bean
    EnvelopeSecurityErrorHandler envelopeSecurityErrorHandler(EnvelopeErrorWriter writer) {
        return new EnvelopeSecurityErrorHandler(writer);
    }

    @Bean
    @Order(2)
    SecurityFilterChain accessTokenSecurity(HttpSecurity http, EnvelopeSecurityErrorHandler errors) throws Exception {
        // 已持有 Access Token 的身份读取自己的 Membership 与权限、管理本租户组织；无状态、不使用登录会话 Cookie。
        http.securityMatcher("/auth/memberships", "/auth/access", "/org/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // 资源服务器对 Bearer Token 有自己的入口与拒绝处理器，只配 exceptionHandling 时无效 Token 仍返回空响应体
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(server -> server.authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors)
                        .jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain manifestSecurity(
            HttpSecurity http, ManifestProperties manifest, EnvelopeSecurityErrorHandler errors) throws Exception {
        // 业务服务以上报令牌调用，无状态、不使用 Cookie，因此无需 CSRF 防护。
        http.securityMatcher("/internal/manifests")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .addFilterBefore(new ServiceTokenFilter(manifest), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain applicationSecurity(HttpSecurity http, EnvelopeSecurityErrorHandler errors) throws Exception {
        // JSON 凭证入口不开启 CORS，授权端点使用 Redis HTTP 会话；Token 交换仍需授权码和 PKCE。
        http.csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/auth/login",
                        "/auth/logout",
                        "/auth/exchange",
                        "/auth/refresh",
                        "/auth/switch",
                        "/auth/login/send-code",
                        "/auth/code-login",
                        "/auth/register/send-code",
                        "/auth/register",
                        "/platform/auth/login",
                        "/platform/auth/logout",
                        "/platform/auth/exchange",
                        "/platform/auth/refresh"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(
                                "/auth/login",
                                "/auth/logout",
                                "/auth/exchange",
                                "/auth/refresh",
                                "/auth/switch",
                                "/auth/login/send-code",
                                "/auth/code-login",
                                "/auth/register/send-code",
                                "/auth/register",
                                "/platform/auth/login",
                                "/platform/auth/logout",
                                "/platform/auth/exchange",
                                "/platform/auth/refresh",
                                "/.well-known/openid-configuration",
                                "/platform/.well-known/openid-configuration",
                                "/platform/oauth2/jwks",
                                "/actuator/health",
                                "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }
}
