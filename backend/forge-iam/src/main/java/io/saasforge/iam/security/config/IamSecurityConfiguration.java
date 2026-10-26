package io.saasforge.iam.security.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.saasforge.iam.identity.mapper.IdentityMapper;
import io.saasforge.iam.identity.model.LoginIdentity;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
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
    OAuth2TokenCustomizer<JwtEncodingContext> tenantClaims(IdentityMapper identities) {
        return context -> {
            UUID identityId = UUID.fromString(context.getPrincipal().getName());
            LoginIdentity identity;
            var authorization = context.getAuthorization();
            if (authorization != null && authorization.getAccessToken() != null) {
                var claims = authorization.getAccessToken().getClaims();
                identity = identities.findForRefresh(
                        identityId, UUID.fromString((String) claims.get("membership_id")), UUID.fromString((String)
                                claims.get("tenant_id")));
            } else {
                var matches = identities.findForIdentity(identityId);
                identity = matches.size() == 1 ? matches.get(0) : null;
            }
            if (identity == null) throw new OAuth2AuthenticationException("invalid_grant");
            context.getClaims()
                    .subject(identity.identityId().toString())
                    .claim("tenant_id", identity.tenantId().toString())
                    .claim("membership_id", identity.membershipId().toString())
                    .claim("ver", identity.version());
        };
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
    SecurityFilterChain applicationSecurity(HttpSecurity http) throws Exception {
        // JSON 凭证入口不开启 CORS，授权端点使用 Redis HTTP 会话；Token 交换仍需授权码和 PKCE。
        http.csrf(csrf ->
                        csrf.ignoringRequestMatchers("/auth/login", "/auth/logout", "/auth/exchange", "/auth/refresh"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(
                                "/auth/login",
                                "/auth/logout",
                                "/auth/exchange",
                                "/auth/refresh",
                                "/actuator/health",
                                "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest()
                        .denyAll());
        return http.build();
    }
}
