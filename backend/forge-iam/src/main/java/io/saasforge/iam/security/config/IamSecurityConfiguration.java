package io.saasforge.iam.security.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.saasforge.iam.identity.model.ActiveMembership;
import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.security.filter.TokenGrantLockFilter;
import io.saasforge.iam.security.service.RedisAuthorizationService;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
class IamSecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(
            @Value("${forge.security.issuer:http://localhost:8080/iam}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    @Bean
    RegisteredClientRepository registeredClients(
            TenantClientCredentials credentials,
            @Value("${forge.iam.redirect-uri:http://localhost:5174/}") String redirectUri) {
        // 凭证仅由同进程的 Token 交换适配器持有；浏览器仍必须提供 PKCE 校验值。
        return new InMemoryRegisteredClientRepository(RegisteredClient.withId("tenant-app")
                .clientId("tenant-app")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientSecret(new BCryptPasswordEncoder().encode(credentials.secret()))
                .redirectUri(redirectUri)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope("tenant")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false)
                        .build())
                .build());
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(
            @Value("${forge.iam.signing-key-file:deploy/data/iam/signing.jwk}") String filename) throws Exception {
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
        JWKSet set = new JWKSet(key);
        return (selector, context) -> selector.select(set);
    }

    @Bean
    TenantClientCredentials clientCredentials(@Value("${forge.iam.client-secret:}") String secret) {
        return new TenantClientCredentials(secret.isBlank() ? UUID.randomUUID().toString() : secret);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tenantClaims(IdentityService identities) {
        return context -> {
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
            context.getClaims()
                    .subject(membership.identityId().toString())
                    .claim("tenant_id", membership.tenantId().toString())
                    .claim("membership_id", membership.membershipId().toString())
                    .claim("ver", membership.version());
        };
    }

    private static Optional<UUID> parseMembershipId(Object value) {
        try {
            return Optional.of(UUID.fromString(value.toString()));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    @Bean
    JwtDecoder accessTokenDecoder(
            JWKSource<SecurityContext> jwkSource,
            @Value("${forge.security.issuer:http://localhost:8080/iam}") String issuer) {
        var decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
        // 与下游一致：只接受本 Realm 签发给 tenant-app 的 Access Token。
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
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
    @Order(2)
    SecurityFilterChain accessTokenSecurity(HttpSecurity http) throws Exception {
        // 已持有 Access Token 的身份读取自己的 Membership；无状态、不使用登录会话 Cookie。
        http.securityMatcher("/auth/memberships")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain applicationSecurity(HttpSecurity http) throws Exception {
        // JSON 凭证入口不开启 CORS，授权端点使用 Redis HTTP 会话；Token 交换仍需授权码和 PKCE。
        http.csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/auth/login", "/auth/logout", "/auth/exchange", "/auth/refresh", "/auth/switch"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(
                                "/auth/login",
                                "/auth/logout",
                                "/auth/exchange",
                                "/auth/refresh",
                                "/auth/switch",
                                "/actuator/health",
                                "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest()
                        .denyAll());
        return http.build();
    }
}
