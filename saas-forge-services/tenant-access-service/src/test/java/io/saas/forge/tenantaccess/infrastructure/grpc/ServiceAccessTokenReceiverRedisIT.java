package io.saas.forge.tenantaccess.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import io.saas.forge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saas.forge.contracts.tenantaccess.membership.v1.ValidateMembershipRequest;
import io.saas.forge.contracts.tenantaccess.membership.v1.ValidateMembershipResponse;
import io.saas.forge.contracts.tenantaccess.provisioning.v1.CheckInitialSubscriptionEligibilityRequest;
import io.saas.forge.contracts.tenantaccess.provisioning.v1.TenantProvisioningQueryServiceGrpc;
import io.saas.forge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saas.forge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saas.forge.sdk.auth.ServiceJwtVerificationKey;
import io.saas.forge.tenantaccess.api.grpc.MembershipValidationGrpcService;
import io.saas.forge.tenantaccess.api.grpc.TenantProvisioningQueryGrpcService;
import io.saas.forge.tenantaccess.application.membership.MembershipValidationQuery;
import io.saas.forge.tenantaccess.application.membership.ValidatedMembership;
import io.saas.forge.tenantaccess.application.tenant.InitialSubscriptionEligibility;
import io.saas.forge.tenantaccess.application.tenant.InitialSubscriptionEligibilityService;
import io.saas.forge.tenantaccess.infrastructure.security.IamServiceClientId;
import io.saas.forge.tenantaccess.infrastructure.security.RedisServiceAccessTokenRevocationChecker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

/** Tenant Access 真实 gRPC 接收端与 Redis Revocation Index 的 fail-closed 验收。 */
@Testcontainers
class ServiceAccessTokenReceiverRedisIT {
    private static final Instant NOW = Instant.parse("2026-08-28T06:00:00Z");
    private static final UUID IAM_CLIENT_ID = uuidV7(1);
    private static final UUID ENTITLEMENT_CLIENT_ID = uuidV7(2);
    private static final UUID IDENTITY_ID = uuidV7(3);
    private static final UUID MEMBERSHIP_ID = uuidV7(4);
    private static final UUID TENANT_ID = uuidV7(5);
    private static final UUID JTI = uuidV7(6);
    private static final String PREFIX = "sf:test:iam-service:";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.8.1"))
            .withCommand("redis-server", "--appendonly", "no", "--maxmemory-policy", "noeviction")
            .withExposedPorts(6379);

    private final AtomicInteger membershipCalls = new AtomicInteger();
    private final AtomicInteger provisioningCalls = new AtomicInteger();
    private final AtomicReference<UUID> operationTarget = new AtomicReference<>();
    private RSAKey key;
    private LettuceConnectionFactory redisConnection;
    private StringRedisTemplate redis;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("tenant-access-receiver-key").generate();
        RedisStandaloneConfiguration redisConfiguration = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .shutdownTimeout(Duration.ZERO)
                .build();
        redisConnection = new LettuceConnectionFactory(redisConfiguration, clientConfiguration);
        redisConnection.afterPropertiesSet();
        redis = new StringRedisTemplate(redisConnection);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        redis.opsForValue().set(PREFIX + "revocation-index-ready:v1:state", "1");

        ServiceAccessTokenAuthorizer tokens = new ServiceAccessTokenAuthorizer(
                new ServiceAccessTokenSignatureVerifier(
                        this::verificationKey,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        "https://iam.test",
                        "saas.forge-api",
                        Duration.ofSeconds(30)),
                new RedisServiceAccessTokenRevocationChecker(redis, "test"));
        MembershipValidationQuery memberships = (identityId, membershipId) -> {
            membershipCalls.incrementAndGet();
            return Optional.of(new ValidatedMembership(membershipId, TENANT_ID));
        };
        InitialSubscriptionEligibilityService eligibility = mock(InitialSubscriptionEligibilityService.class);
        when(eligibility.check(any())).thenAnswer(invocation -> {
            provisioningCalls.incrementAndGet();
            operationTarget.set(invocation.getArgument(0));
            return InitialSubscriptionEligibility.PENDING_ELIGIBLE;
        });

        MembershipValidationServerInterceptor membershipInterceptor =
                new MembershipValidationServerInterceptor(tokens, new io.saas.forge.tenantaccess.infrastructure.security.MembershipValidationClients(java.util.Set.of(IAM_CLIENT_ID)));
        TenantProvisioningQueryServerInterceptor provisioningInterceptor =
                new TenantProvisioningQueryServerInterceptor(tokens);
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(
                        new MembershipValidationGrpcService(memberships), membershipInterceptor))
                .addService(ServerInterceptors.intercept(
                        new TenantProvisioningQueryGrpcService(eligibility), provisioningInterceptor))
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
    void authorizesWithoutUserTenantContextAndFailsClosedBeforeDomainHandling() throws Exception {
        String membershipToken = serviceToken(IAM_CLIENT_ID, "tenant-access:membership:read");
        String provisioningToken = serviceToken(ENTITLEMENT_CLIENT_ID, "tenant-access:tenant:read");
        assertServiceTokenHasNoUserTenantContext(membershipToken);
        assertServiceTokenHasNoUserTenantContext(provisioningToken);

        ValidateMembershipResponse membership = validateMembership(membershipToken);
        assertEquals(MEMBERSHIP_ID.toString(), membership.getValidatedMembership().getMembershipId());
        assertEquals(TENANT_ID.toString(), membership.getValidatedMembership().getTenantId());
        assertEquals(
                io.saas.forge.contracts.tenantaccess.provisioning.v1.InitialSubscriptionEligibility
                        .PENDING_ELIGIBLE,
                checkEligibility(provisioningToken).getEligibility());
        assertEquals(TENANT_ID, operationTarget.get());
        assertEquals(1, membershipCalls.get());
        assertEquals(1, provisioningCalls.get());

        assertStatus(Status.Code.PERMISSION_DENIED,
                () -> validateMembership(serviceToken(IAM_CLIENT_ID, "tenant-access:tenant:read")));
        assertStatus(Status.Code.PERMISSION_DENIED,
                () -> checkEligibility(serviceToken(ENTITLEMENT_CLIENT_ID, "tenant-access:membership:read")));
        assertDomainCallCounts(1, 1);

        redis.opsForValue().set(PREFIX + "signing-kid-revocation:v1:" + digest(key.getKeyID()), "1");
        assertUnauthenticated(membershipToken, provisioningToken);
        redis.delete(PREFIX + "signing-kid-revocation:v1:" + digest(key.getKeyID()));

        redis.opsForValue().set(PREFIX + "oauth-client-revocation:v1:" + IAM_CLIENT_ID, "1");
        assertStatus(Status.Code.UNAUTHENTICATED, () -> validateMembership(membershipToken));
        redis.opsForValue().set(PREFIX + "oauth-client-revocation:v1:" + ENTITLEMENT_CLIENT_ID, "1");
        assertStatus(Status.Code.UNAUTHENTICATED, () -> checkEligibility(provisioningToken));
        assertDomainCallCounts(1, 1);

        redis.delete(PREFIX + "oauth-client-revocation:v1:" + IAM_CLIENT_ID);
        redis.delete(PREFIX + "oauth-client-revocation:v1:" + ENTITLEMENT_CLIENT_ID);
        redis.opsForValue().set(PREFIX + "revocation-index-ready:v1:state", "0");
        assertUnauthenticated(membershipToken, provisioningToken);

        redis.opsForValue().set(PREFIX + "revocation-index-ready:v1:state", "1");
        REDIS.stop();
        assertUnauthenticated(membershipToken, provisioningToken);
    }

    private void assertUnauthenticated(String membershipToken, String provisioningToken) {
        assertStatus(Status.Code.UNAUTHENTICATED, () -> validateMembership(membershipToken));
        assertStatus(Status.Code.UNAUTHENTICATED, () -> checkEligibility(provisioningToken));
        assertDomainCallCounts(1, 1);
    }

    private void assertDomainCallCounts(int expectedMembershipCalls, int expectedProvisioningCalls) {
        assertEquals(expectedMembershipCalls, membershipCalls.get());
        assertEquals(expectedProvisioningCalls, provisioningCalls.get());
    }

    private ValidateMembershipResponse validateMembership(String token) {
        return MembershipValidationServiceGrpc.newBlockingStub(channel)
                .withInterceptors(authorization(token))
                .validateMembership(ValidateMembershipRequest.newBuilder()
                        .setIdentityId(IDENTITY_ID.toString())
                        .setMembershipId(MEMBERSHIP_ID.toString())
                        .build());
    }

    private io.saas.forge.contracts.tenantaccess.provisioning.v1.CheckInitialSubscriptionEligibilityResponse
            checkEligibility(String token) {
        return TenantProvisioningQueryServiceGrpc.newBlockingStub(channel)
                .withInterceptors(authorization(token))
                .checkInitialSubscriptionEligibility(CheckInitialSubscriptionEligibilityRequest.newBuilder()
                        .setTenantId(TENANT_ID.toString())
                        .build());
    }

    private static io.grpc.ClientInterceptor authorization(String token) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return MetadataUtils.newAttachHeadersInterceptor(metadata);
    }

    private Optional<ServiceJwtVerificationKey> verificationKey(String kid) {
        if (!key.getKeyID().equals(kid)) {
            return Optional.empty();
        }
        return Optional.of(new ServiceJwtVerificationKey(
                key.getKeyID(), key.getModulus().toString(), key.getPublicExponent().toString()));
    }

    private String serviceToken(UUID clientId, String scope) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://iam.test")
                .audience("saas.forge-api")
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .jwtID(JTI.toString())
                .subject(clientId.toString())
                .claim("client_id", clientId.toString())
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

    private static void assertServiceTokenHasNoUserTenantContext(String token) throws Exception {
        var claims = SignedJWT.parse(token).getJWTClaimsSet().getClaims();
        assertFalse(claims.containsKey("identityId"));
        assertFalse(claims.containsKey("membershipId"));
        assertFalse(claims.containsKey("tenantId"));
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertStatus(Status.Code expected, org.junit.jupiter.api.function.Executable invocation) {
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, invocation);
        assertEquals(expected, exception.getStatus().getCode());
    }

    private static UUID uuidV7(long sequence) {
        return UUID.fromString("0198f89c-8200-7000-8000-" + String.format("%012x", sequence));
    }
}
