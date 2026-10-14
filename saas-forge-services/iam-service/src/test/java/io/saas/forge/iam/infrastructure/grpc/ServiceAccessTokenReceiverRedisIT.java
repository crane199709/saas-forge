package io.saas.forge.iam.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.saas.forge.contracts.iam.authorization.v1.CheckPlatformRoleRequest;
import io.saas.forge.contracts.iam.authorization.v1.PlatformAuthorizationServiceGrpc;
import io.saas.forge.iam.application.authorization.PlatformRoleAuthorizationService;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignment;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.infrastructure.security.RedisRevocationIndex;
import io.saas.forge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saas.forge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saas.forge.sdk.auth.ServiceJwtVerificationKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 真实 gRPC 接收端与 Redis Revocation Index 的 fail-closed 验收。 */
@Testcontainers
class ServiceAccessTokenReceiverRedisIT {
    private static final Instant NOW = Instant.parse("2026-08-28T05:00:00Z");
    private static final UUID CLIENT_ID =
            UUID.fromString("0198f89c-8100-7000-8000-000000000001");
    private static final UUID IDENTITY_ID =
            UUID.fromString("0198f89c-8100-7000-8000-000000000002");
    private static final UUID JTI =
            UUID.fromString("0198f89c-8100-7000-8000-000000000003");
    private static final String REQUIRED_SCOPE = "iam:platform-role:read";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.8.1"))
            .withCommand("redis-server", "--appendonly", "no", "--maxmemory-policy", "noeviction")
            .withExposedPorts(6379);

    private RSAKey key;
    private LettuceConnectionFactory redisConnection;
    private RedisRevocationIndex revocations;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("service-receiver-key").generate();
        RedisStandaloneConfiguration redisConfiguration = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .shutdownTimeout(Duration.ZERO)
                .build();
        redisConnection = new LettuceConnectionFactory(redisConfiguration, clientConfiguration);
        redisConnection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(redisConnection);
        redis.afterPropertiesSet();
        revocations = new RedisRevocationIndex(redis, "test");
        revocations.rebuild(List.of(), List.of(), List.of(), NOW);

        ServiceAccessTokenAuthorizer tokens = new ServiceAccessTokenAuthorizer(
                new ServiceAccessTokenSignatureVerifier(
                        this::verificationKey,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        "https://iam.test",
                        "saas.forge-api",
                        Duration.ofSeconds(30)),
                revocations::isServiceTokenRevoked);
        PlatformAuthorizationServerInterceptor interceptor =
                new PlatformAuthorizationServerInterceptor(tokens);
        PlatformRoleAuthorizationService application = new PlatformRoleAuthorizationService(
                new AllowPlatformAdminRepository(), Clock.fixed(NOW, ZoneOffset.UTC));
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(
                        new PlatformAuthorizationGrpcService(application), interceptor))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (redisConnection != null) {
            redisConnection.destroy();
        }
    }

    @Test
    void authorizesExactScopeAndFailsClosedForClientRevocationReadyAndRedisFailure() throws Exception {
        String token = serviceToken(REQUIRED_SCOPE);
        assertTrue(call(token).getAllowed());

        StatusRuntimeException wrongScope = assertThrows(
                StatusRuntimeException.class, () -> call(serviceToken("iam:identity:write")));
        assertEquals(Status.Code.PERMISSION_DENIED, wrongScope.getStatus().getCode());

        revocations.revokeClient(CLIENT_ID);
        assertUnauthenticated(token);

        revocations.rebuild(List.of(), List.of(), List.of(), NOW);
        assertTrue(call(token).getAllowed());
        revocations.markNotReady();
        assertUnauthenticated(token);

        revocations.rebuild(List.of(), List.of(), List.of(), NOW);
        assertTrue(call(token).getAllowed());
        revocations.revokeSigningKey(key.getKeyID(), NOW.plusSeconds(300), List.of(), NOW);
        assertUnauthenticated(token);

        key = new RSAKeyGenerator(2048).keyID("replacement-service-receiver-key").generate();
        String replacementToken = serviceToken(REQUIRED_SCOPE);
        assertTrue(call(replacementToken).getAllowed());
        REDIS.stop();
        assertUnauthenticated(replacementToken);
    }

    private void assertUnauthenticated(String token) {
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> call(token));
        assertEquals(Status.Code.UNAUTHENTICATED, exception.getStatus().getCode());
    }

    private io.saas.forge.contracts.iam.authorization.v1.CheckPlatformRoleResponse call(String token) {
        Metadata metadata = new Metadata();
        metadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + token);
        return PlatformAuthorizationServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .checkPlatformRole(CheckPlatformRoleRequest.newBuilder()
                        .setIdentityId(IDENTITY_ID.toString())
                        .setRoleKey("PLATFORM_ADMIN")
                        .build());
    }

    private Optional<ServiceJwtVerificationKey> verificationKey(String kid) {
        if (!key.getKeyID().equals(kid)) {
            return Optional.empty();
        }
        return Optional.of(new ServiceJwtVerificationKey(
                key.getKeyID(), key.getModulus().toString(), key.getPublicExponent().toString()));
    }

    private String serviceToken(String scope) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://iam.test")
                .audience("saas.forge-api")
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .jwtID(JTI.toString())
                .subject(CLIENT_ID.toString())
                .claim("client_id", CLIENT_ID.toString())
                .claim("scope", scope)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(new JOSEObjectType("at+jwt"))
                        .keyID(key.getKeyID())
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static final class AllowPlatformAdminRepository implements PlatformRoleAssignmentRepository {
        @Override
        public PlatformRoleAssignment grant(PlatformRoleAssignment assignment) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasActiveAssignment(UUID identityId, String roleKey, Instant at) {
            return IDENTITY_ID.equals(identityId) && "PLATFORM_ADMIN".equals(roleKey);
        }

        @Override
        public java.util.Optional<PlatformRoleAssignment> findActive(UUID identityId, String roleKey, Instant at) {
            return java.util.Optional.empty();
        }
    }
}
