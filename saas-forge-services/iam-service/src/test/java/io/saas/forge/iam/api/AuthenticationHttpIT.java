package io.saas.forge.iam.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saas.forge.iam.application.signing.ActiveSigningKeyResolver;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.saas.forge.contracts.tenantaccess.membership.v1.AccessibleMembershipQueryServiceGrpc;
import io.saas.forge.contracts.tenantaccess.membership.v1.ListAccessibleMembershipsRequest;
import io.saas.forge.contracts.tenantaccess.membership.v1.ListAccessibleMembershipsResponse;
import io.saas.forge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saas.forge.contracts.tenantaccess.membership.v1.ValidateMembershipRequest;
import io.saas.forge.contracts.tenantaccess.membership.v1.ValidateMembershipResponse;
import io.saas.forge.iam.application.authentication.*;
import io.saas.forge.iam.domain.session.ConsoleSessionRepository;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.application.authentication.InitialPasswordChangeService;
import io.saas.forge.iam.application.authentication.MembershipValidation;
import io.saas.forge.iam.application.authentication.PasswordSetupChallengeToken;
import io.saas.forge.iam.application.authentication.PasswordSetupService;
import io.saas.forge.iam.application.authentication.PresentedAccessToken;
import io.saas.forge.iam.application.authentication.PresentedAccessTokenVerifier;
import io.saas.forge.iam.application.authentication.RevocationIndex;
import io.saas.forge.iam.application.authentication.RevocationIndexRecovery;
import io.saas.forge.iam.application.authentication.RevocationIndexUnavailableException;
import io.saas.forge.iam.application.authentication.RevocationFenceConflictException;
import io.saas.forge.iam.application.authentication.RevocationFenceOperations;
import io.saas.forge.iam.application.authentication.TenantContextSwitchService;
import io.saas.forge.iam.application.authentication.TenantContextSwitchTransaction;
import io.saas.forge.iam.application.authentication.UserSessionRevocationResult;
import io.saas.forge.iam.application.authentication.UserSessionRevocationService;
import io.saas.forge.iam.application.signing.JwtSigningPort;
import io.saas.forge.iam.application.signing.JwtSigningService;
import io.saas.forge.iam.application.signing.SigningKeyLifecycleService;
import io.saas.forge.iam.application.signing.SigningKeyRevocationTransaction;
import io.saas.forge.iam.config.AuthenticationConfiguration;
import io.saas.forge.iam.config.PasswordSetupMailConfiguration;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignment;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.domain.client.ClientSecretDigest;
import io.saas.forge.iam.domain.client.OAuthClient;
import io.saas.forge.iam.domain.client.OAuthClientRepository;
import io.saas.forge.iam.domain.client.OAuthScope;
import io.saas.forge.iam.domain.identity.Argon2idPasswordHash;
import io.saas.forge.iam.domain.identity.Identity;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.identity.PasswordCredential;
import io.saas.forge.iam.domain.signing.SigningKeyRepository;
import io.saas.forge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saas.forge.iam.domain.session.RefreshTokenFamily;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saas.forge.iam.domain.session.RevocationFence;
import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import io.saas.forge.iam.domain.session.TenantContextSwitchRepository;
import io.saas.forge.iam.domain.session.TenantContextSwitchWorkflow;
import io.saas.forge.iam.domain.session.UserSessionRevocationRepository;
import io.saas.forge.iam.domain.session.UserSessionRevocationStatus;
import io.saas.forge.iam.infrastructure.messaging.OutboxPublisher;
import io.saas.forge.iam.infrastructure.persistence.MyBatisIdentityRepository;
import io.saas.forge.iam.infrastructure.grpc.GrpcAccessibleMemberships;
import io.saas.forge.iam.infrastructure.grpc.GrpcMembershipValidation;
import io.saas.forge.iam.infrastructure.security.ReservedIamServiceAccessTokenProvider;
import io.saas.forge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saas.forge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saas.forge.sdk.auth.ServiceJwtVerificationKey;
import io.saas.forge.tenantaccess.api.grpc.AccessibleMembershipGrpcService;
import io.saas.forge.tenantaccess.api.grpc.MembershipValidationGrpcService;
import io.saas.forge.tenantaccess.infrastructure.grpc.MembershipValidationServerInterceptor;
import io.saas.forge.tenantaccess.infrastructure.persistence.MyBatisAccessibleMembershipQuery;
import io.saas.forge.tenantaccess.infrastructure.persistence.MyBatisMembershipValidationQuery;
import io.saas.forge.tenantaccess.infrastructure.persistence.mapper.AccessibleMembershipMapper;
import io.saas.forge.tenantaccess.infrastructure.security.IamServiceClientId;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.Signature;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

/**
 * IAM HTTP 到 Tenant Access gRPC、PostgreSQL 18、Redis 与 Outbox 的进程内跨服务验收。
 * 本用例不证明 Gateway 按 jti 拒绝、Redis 故障时公开入口 fail-closed 或浏览器多标签页协调。
 */
@Testcontainers
@SpringJUnitConfig(AuthenticationHttpIT.TestConfiguration.class)
@TestPropertySource(properties = {
        "saas.forge.environment=test",
        "browser.rootDomain=saas.forge.test",
        "security.jwt.issuer=https://iam.test.saas.forge.invalid",
        "security.jwt.access-token-ttl=PT15M",
        "security.login-protection.failure-window=PT15M",
        "security.login-protection.maximum-failures=5",
        "security.login-protection.lock-duration=PT15M",
        "security.revocation-index.recovery-delay=PT1H",
        "saas.forge.iam.session-revocation.worker-delay=PT1H",
        "saas.forge.iam.tenant-context-switch.recovery-delay=PT1H",
        "saas.forge.iam.outbox.publish-delay=PT0.1S"
})
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthenticationHttpIT {
    private static final String REDIS_PASSWORD = "redis-auth-test-password";
    private static final String IDEMPOTENCY_KEY = "0198c9d5-0f25-7b21-8d67-31c8652d4c8f";
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final Pattern COOKIE_VALUE = Pattern.compile(
            "^__Host-sf_(?:platform|tenant)_refresh=([^;]+)");
    private static final Path REPOSITORY_ROOT = repositoryRoot();
    private static final UUID IAM_SERVICE_CLIENT_ID = uuidV7(90_001);
    private static final String IAM_SERVICE_CLIENT_SECRET = serviceClientSecret((byte) 90);
    private static final Path IAM_SERVICE_CLIENT_ID_FILE = secretFile(
            "iam-service-client-id", IAM_SERVICE_CLIENT_ID.toString());
    private static final Path IAM_SERVICE_CLIENT_SECRET_FILE = secretFile(
            "iam-service-client-secret", IAM_SERVICE_CLIENT_SECRET);
    private static final RSAKey SIGNING_KEY = signingKey();
    private static final Set<UUID> TENANT_ACCESS_FAILURES = ConcurrentHashMap.newKeySet();
    private static final AtomicReference<Boolean> SERVICE_SIGNING_TRANSACTION = new AtomicReference<>();
    private static final AtomicBoolean SIGNING_FAILURE = new AtomicBoolean();
    private static final AtomicReference<ConcurrencyGate> MEMBERSHIP_VALIDATION_GATE = new AtomicReference<>();
    private static final AtomicReference<ConcurrencyGate> SIGNING_GATE = new AtomicReference<>();
    private static final AtomicReference<ReservedIamServiceAccessTokenProvider> SERVICE_TOKENS =
            new AtomicReference<>();
    private static final ManagedChannel TENANT_ACCESS_CHANNEL;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
            .withDatabaseName("saas.forge")
            .withUsername("saas.forge_admin")
            .withPassword("admin-password")
            .withEnv("IAM_MIGRATOR_PASSWORD", "iam-migrator-password")
            .withEnv("IAM_APP_PASSWORD", "iam-app-password")
            .withEnv("TENANT_ACCESS_MIGRATOR_PASSWORD", "tenant-access-migrator-password")
            .withEnv("TENANT_ACCESS_APP_PASSWORD", "tenant-access-app-password")
            .withEnv("ENTITLEMENT_MIGRATOR_PASSWORD", "entitlement-migrator-password")
            .withEnv("ENTITLEMENT_APP_PASSWORD", "entitlement-app-password")
            .withEnv("AUDIT_MIGRATOR_PASSWORD", "audit-migrator-password")
            .withEnv("AUDIT_APP_PASSWORD", "audit-app-password")
            .withCopyFileToContainer(
                    org.testcontainers.utility.MountableFile.forHostPath(
                            REPOSITORY_ROOT.resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.8.1"))
            .withCommand("redis-server", "--appendonly", "yes", "--maxmemory-policy", "noeviction",
                    "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
        migrateAndSeedSigningKey();
        migrateTenantAccess();
        try {
            AccessibleMembershipMapper memberships = tenantAccessMembershipMapper();
            AccessibleMembershipGrpcService accessibleMemberships = new AccessibleMembershipGrpcService(
                    new MyBatisAccessibleMembershipQuery(memberships));
            MembershipValidationGrpcService membershipValidation = new MembershipValidationGrpcService(
                    new MyBatisMembershipValidationQuery(memberships));
            ServiceAccessTokenAuthorizer tokens = new ServiceAccessTokenAuthorizer(
                    new ServiceAccessTokenSignatureVerifier(
                            AuthenticationHttpIT::verificationKey,
                            java.time.Clock.systemUTC(),
                            "https://iam.test.saas.forge.invalid",
                            "saas.forge-api",
                            Duration.ofSeconds(30)),
                    (clientId, kid) -> false);
            MembershipValidationServerInterceptor authentication = new MembershipValidationServerInterceptor(
                    tokens, new IamServiceClientId(IAM_SERVICE_CLIENT_ID));
            String serverName = InProcessServerBuilder.generateName();
            Server ignored = InProcessServerBuilder.forName(serverName)
                    .directExecutor()
                    .addService(new FaultInjectingAccessibleMembershipService(accessibleMemberships))
                    .addService(ServerInterceptors.intercept(
                            new FaultInjectingMembershipValidationService(membershipValidation), authentication))
                    .build()
                    .start();
            TENANT_ACCESS_CHANNEL = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    IdentityRepository identities;

    @Autowired
    PlatformRoleAssignmentRepository platformRoles;

    @Autowired
    OAuthClientRepository oauthClients;

    @Autowired
    InitialPasswordChangeService passwordChangeService;

    @Autowired
    PasswordSetupService passwordSetupService;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    DataSource dataSource;

    @Autowired
    RevocationIndex revocationIndex;

    @Autowired
    RevocationIndexRecovery revocationIndexRecovery;

    @Autowired
    RevocationFenceOperations revocationFenceService;

    @Autowired
    SigningKeyLifecycleService signingKeyLifecycleService;

    @Autowired
    TenantContextSwitchService tenantContextSwitchService;

    @Autowired
    TenantContextSwitchRepository tenantContextSwitches;

    @Autowired
    TenantContextSwitchTransaction tenantContextSwitchTransaction;

    @Autowired
    UserSessionRevocationService userSessionRevocationService;

    @Autowired
    UserSessionRevocationRepository userSessionRevocations;

    @Autowired
    RefreshTokenFamilyRepository refreshTokenFamilies;

    MockMvc mockMvc;
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        SERVICE_TOKENS.set(webApplicationContext.getBean(ReservedIamServiceAccessTokenProvider.class));
        jdbc = new JdbcTemplate(dataSource);
        Set<String> keys = redis.keys("sf:test:iam-service:login-*:v1:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        TENANT_ACCESS_FAILURES.clear();
        SIGNING_FAILURE.set(false);
        MEMBERSHIP_VALIDATION_GATE.set(null);
        SIGNING_GATE.set(null);
        ensureReservedIamServiceClient();
    }

    @Test
    @Order(10000)
    void unifiedConsoleLifecycleRejectsStaleCommandsAndRevokesEveryIssuedToken() throws Exception {
        var http = unifiedConsole();
        createUser("unified-platform@example.test", "Console-password!", true, Credential.REGULAR);
        var bootstrap = http.perform(consolePost("bootstrap").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionPresent").value(false)).andReturn();
        Cookie locator = bootstrap.getResponse().getCookie("__Host-sf_console_slot");
        assertNotNull(locator);
        var login = http.perform(consolePost("login").cookie(locator).header("If-Match", "\"0\"")
                .content("{\"email\":\"unified-platform@example.test\",\"password\":\"Console-password!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.activeContext.type").value("PLATFORM")).andReturn();
        mockMvc.perform(get("/api/v1/platform/oauth-clients").header("Authorization", "Bearer " + accessToken(login)))
                .andExpect(status().isOk());
        Cookie refresh = login.getResponse().getCookie("__Host-sf_console_refresh");
        String revision = login.getResponse().getHeader("ETag");
        String firstAccess = json(login.getResponse().getContentAsByteArray()).get("accessToken").asString();
        http.perform(consolePost("login").cookie(locator).header("If-Match", "\"0\"")
                .content("{\"email\":\"unified-platform@example.test\",\"password\":\"wrong\"}"))
                .andExpect(status().isPreconditionFailed()).andExpect(header().doesNotExist("Set-Cookie"));
        http.perform(get("/api/v2/auth/session").cookie(locator, refresh)
                .header("Origin", "https://console.saas.forge.test").header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").doesNotExist());
        var rotated = http.perform(consolePost("refresh").cookie(locator, refresh).header("If-Match", revision)
                .header("Idempotency-Key", uuidV7(80401)).content("{}"))
                .andExpect(status().isOk()).andReturn();
        String secondAccess = json(rotated.getResponse().getContentAsByteArray()).get("accessToken").asString();
        var ended = http.perform(consolePost("logout").cookie(locator).header("If-Match", revision)
                .header("Idempotency-Key", uuidV7(80402)).content("{}"))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("__Host-sf_console_refresh", 0)).andReturn();
        for (String access : List.of(firstAccess, secondAccess)) {
            mockMvc.perform(get("/api/v1/auth/session").header("Authorization", "Bearer " + access))
                    .andExpect(status().isUnauthorized());
        }
        createUser("unified-next@example.test", "Console-password!", true, Credential.REGULAR);
        var next = http.perform(consolePost("login").cookie(locator).header("If-Match", ended.getResponse().getHeader("ETag"))
                .content("{\"email\":\"unified-next@example.test\",\"password\":\"Console-password!\"}"))
                .andExpect(status().isOk()).andReturn();
        http.perform(consolePost("logout").cookie(locator).header("If-Match", revision)
                .header("Idempotency-Key", uuidV7(80402)).content("{}"))
                .andExpect(status().isNoContent()).andExpect(header().doesNotExist("Set-Cookie"));
        http.perform(get("/api/v2/auth/session").cookie(locator, refresh)
                .header("Origin", "https://console.saas.forge.test").header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_COOKIE_MISMATCH"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        http.perform(get("/api/v2/auth/session").cookie(locator, next.getResponse().getCookie("__Host-sf_console_refresh"))
                .header("Origin", "https://console.saas.forge.test").header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.identity.email").value("unified-next@example.test"));
    }

    @Test
    @Order(10000)
    void unifiedConsoleKeepsNoPermissionAndInitialCredentialSessionsRestricted() throws Exception {
        var http = unifiedConsole();
        for (boolean initial : List.of(false, true)) {
            String email = initial ? "unified-initial@example.test" : "unified-empty@example.test";
            createUser(email, "Console-password!", initial, initial ? Credential.ACTIVE_INITIAL : Credential.REGULAR);
            var bootstrap = http.perform(consolePost("bootstrap").content("{}")).andReturn();
            Cookie locator = bootstrap.getResponse().getCookie("__Host-sf_console_slot");
            var login = http.perform(consolePost("login").cookie(locator).header("If-Match", "\"0\"")
                    .content("{\"email\":\"" + email + "\",\"password\":\"Console-password!\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.state").value(initial
                            ? "PASSWORD_CHANGE_REQUIRED" : "NO_AVAILABLE_CONTEXT"))
                    .andExpect(jsonPath("$.accessToken").doesNotExist())
                    .andExpect(jsonPath("$.activeContext").doesNotExist()).andReturn();
            if (initial) assertEquals(Set.of("sessionId", "revision", "state"),
                    json(login.getResponse().getContentAsByteArray()).propertyNames());
            if (initial) http.perform(consolePost("refresh").cookie(locator, login.getResponse().getCookie("__Host-sf_console_refresh"))
                    .header("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
                    .header("If-Match", login.getResponse().getHeader("ETag")).header("Idempotency-Key", uuidV7(80403)).content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INITIAL_CREDENTIAL_RESTRICTED"))
                    .andExpect(jsonPath("$.traceId").value("0123456789abcdef0123456789abcdef"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        http.perform(consolePost("bootstrap").header("Origin", "https://platform.saas.forge.test").content("{}"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        http.perform(consolePost("login").header("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01").content("{}"))
                .andExpect(status().is(428)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.traceId").value("0123456789abcdef0123456789abcdef"));
    }

    @Test
    @Order(10000)
    void unifiedConsoleLogoutRemainsPendingUntilRevocationIsConfirmed() throws Exception {
        var http = unifiedConsole();
        createUser("unified-pending@example.test", "Console-password!", true, Credential.REGULAR);
        var bootstrap = http.perform(consolePost("bootstrap").content("{}")).andReturn();
        Cookie locator = bootstrap.getResponse().getCookie("__Host-sf_console_slot");
        var login = http.perform(consolePost("login").cookie(locator).header("If-Match", "\"0\"")
                .content("{\"email\":\"unified-pending@example.test\",\"password\":\"Console-password!\"}"))
                .andExpect(status().isOk()).andReturn();
        String revision = login.getResponse().getHeader("ETag");
        revocationIndex.markNotReady();
        try {
            http.perform(consolePost("logout").cookie(locator).header("If-Match", revision)
                    .header("Idempotency-Key", uuidV7(80404)).content("{}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Set-Cookie"));
            var pending = http.perform(consolePost("bootstrap").cookie(locator).content("{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.transition").value("ENDING")).andReturn();
            http.perform(consolePost("login").cookie(locator).header("If-Match", pending.getResponse().getHeader("ETag"))
                    .content("{\"email\":\"unified-pending@example.test\",\"password\":\"Console-password!\"}"))
                    .andExpect(status().isServiceUnavailable());
        } finally { revocationIndexRecovery.recover(); }
        http.perform(consolePost("logout").cookie(locator).header("If-Match", revision)
                .header("Idempotency-Key", uuidV7(80404)).content("{}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(9999)
    void unifiedBootstrapEndsBothLegacyCookieFamiliesBeforeCreatingSlot() throws Exception {
        var platform = createUser("unified-legacy-platform@example.test", "Console-password!", true, Credential.REGULAR);
        var tenant = createUser("unified-legacy-tenant@example.test", "Console-password!", false, Credential.REGULAR);
        accessibleMemberships(tenant.identity().id(), membership(uuidV7(80410), uuidV7(80411), "Legacy tenant"));
        var legacyPlatform = login("unified-legacy-platform@example.test", "Console-password!", "PLATFORM")
                .andExpect(status().isOk()).andReturn();
        var legacyTenant = login("unified-legacy-tenant@example.test", "Console-password!", "TENANT")
                .andExpect(status().isOk()).andReturn();
        var http = unifiedConsole();
        MvcResult retired = null;
        for (int batch = 0; batch < 20; batch++) {
            retired = http.perform(consolePost("bootstrap").cookie(
                    legacyPlatform.getResponse().getCookie("__Host-sf_platform_refresh"),
                    legacyTenant.getResponse().getCookie("__Host-sf_tenant_refresh")).content("{}")).andReturn();
            if (retired.getResponse().getStatus() == 200) break;
            assertEquals(503, retired.getResponse().getStatus());
            assertEquals(null, retired.getResponse().getHeader("Set-Cookie"));
        }
        assertNotNull(retired);
        assertEquals(200, retired.getResponse().getStatus());
        assertEquals(0, retired.getResponse().getCookie("__Host-sf_platform_refresh").getMaxAge());
        assertEquals(0, retired.getResponse().getCookie("__Host-sf_tenant_refresh").getMaxAge());
        unifiedConsole().perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("AUTH_PROTOCOL_RETIRED"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM iam_refresh_token_families WHERE session_protocol = 'LEGACY_V1' AND console_retired_at IS NULL", Integer.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO iam_refresh_tokens(family_id, token_digest, issued_at) SELECT id, ?, CURRENT_TIMESTAMP FROM iam_refresh_token_families WHERE identity_id = ? LIMIT 1",
                new byte[32], platform.identity().id()));
        for (var legacy : List.of(legacyPlatform, legacyTenant)) {
            var claims = json(Base64.getUrlDecoder().decode(json(legacy.getResponse().getContentAsByteArray())
                    .get("accessToken").asString().split("\\.")[1]));
            assertTrue(revocationIndex.isJtiRevoked(UUID.fromString(claims.get("jti").asString())));
        }
    }

    @Test
    @Order(10000)
    void unifiedRevocationSurvivesReplayDeliveryFailureAndLostPlatformAuthority() throws Exception {
        var failDelivery = new AtomicBoolean();
        var http = unifiedConsole(failDelivery);
        var user = createUser("unified-replay@example.test", "Console-password!", true, Credential.REGULAR);
        var bootstrap = http.perform(consolePost("bootstrap").content("{}")).andReturn();
        Cookie locator = bootstrap.getResponse().getCookie("__Host-sf_console_slot");
        var login = http.perform(consolePost("login").cookie(locator).header("If-Match", "\"0\"")
                .content("{\"email\":\"unified-replay@example.test\",\"password\":\"Console-password!\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie oldRefresh = login.getResponse().getCookie("__Host-sf_console_refresh");
        String revision = login.getResponse().getHeader("ETag");
        var rotation = http.perform(consolePost("refresh").cookie(locator, oldRefresh).header("If-Match", revision)
                .header("Idempotency-Key", uuidV7(80420)).content("{}"))
                .andExpect(status().isOk()).andReturn();
        // 强制租约过期后以不同操作键重放；不会等待真实租约窗口。
        var leaseKeys = redis.keys("sf:test:iam-service:refresh-rotation-lease:v1:*");
        if (leaseKeys != null && !leaseKeys.isEmpty()) redis.delete(leaseKeys);
        failDelivery.set(true);
        try {
            http.perform(consolePost("refresh").cookie(locator, oldRefresh).header("If-Match", revision)
                    .header("Idempotency-Key", uuidV7(80421)).content("{}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Set-Cookie"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM iam_access_token_issuances WHERE identity_id = ? AND revoked_at IS NULL", Integer.class, user.identity().id()));
        } finally { failDelivery.set(false); revocationIndexRecovery.recover(); }
        http.perform(consolePost("refresh").cookie(locator, rotation.getResponse().getCookie("__Host-sf_console_refresh"))
                .header("If-Match", revision).header("Idempotency-Key", uuidV7(80422)).content("{}"))
                .andExpect(status().isPreconditionFailed()).andExpect(header().doesNotExist("Set-Cookie"));
        for (var issued : List.of(login, rotation)) mockMvc.perform(get("/api/v1/platform/oauth-clients")
                .header("Authorization", "Bearer " + accessToken(issued))).andExpect(status().isUnauthorized());

        var clean = http.perform(consolePost("bootstrap").content("{}")).andReturn();
        Cookie nextLocator = clean.getResponse().getCookie("__Host-sf_console_slot");
        var current = http.perform(consolePost("login").cookie(nextLocator).header("If-Match", "\"0\"")
                .content("{\"email\":\"unified-replay@example.test\",\"password\":\"Console-password!\"}"))
                .andExpect(status().isOk()).andReturn();
        jdbc.update("UPDATE iam_platform_role_assignments SET revoked_at = CURRENT_TIMESTAMP WHERE identity_id = ?", user.identity().id());
        http.perform(get("/api/v2/auth/session").cookie(nextLocator, current.getResponse().getCookie("__Host-sf_console_refresh"))
                .header("Origin", "https://console.saas.forge.test").header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CURRENT_CONTEXT_REVOKED"));
        mockMvc.perform(get("/api/v1/platform/oauth-clients").header("Authorization", "Bearer " + accessToken(current)))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder consolePost(String operation) {
        return post("/api/v2/auth/" + operation).header("Origin", "https://console.saas.forge.test")
                .header("Sec-Fetch-Site", "same-site").header("X-SF-CSRF", "1").contentType(MediaType.APPLICATION_JSON);
    }

    private final java.util.List<org.springframework.web.context.support.AnnotationConfigWebApplicationContext> consoleContexts = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void closeConsoleContexts() {
        consoleContexts.forEach(org.springframework.context.support.AbstractApplicationContext::close);
        consoleContexts.clear();
    }

    private MockMvc unifiedConsole() {
        return unifiedConsole(new AtomicBoolean());
    }

    private MockMvc unifiedConsole(AtomicBoolean failDelivery) {
        var context = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
        context.setParent(webApplicationContext);
        // 只在外部撤销写入边界注入故障；签名、租约、数据库与实际恢复仍使用真实设施。
        context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("revocationIndex",
                java.lang.reflect.Proxy.newProxyInstance(RevocationIndex.class.getClassLoader(),
                        new Class<?>[]{RevocationIndex.class}, (proxy, method, arguments) -> {
                            if (failDelivery.get() && method.getName().equals("revokeJti"))
                                throw new RevocationIndexUnavailableException();
                            try { return method.invoke(revocationIndex, arguments); }
                            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        })));
        context.setServletContext(webApplicationContext.getServletContext());
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "console-test", Map.of("security.browser.console-enabled", "true")));
        context.register(io.saas.forge.iam.config.ConsoleSessionConfiguration.class,
                ConsoleAuthenticationController.class, ConsoleBrowserRequestFilter.class,
                ConsoleAuthenticationExceptionHandler.class, ConsoleWebConfiguration.class);
        context.refresh();
        consoleContexts.add(context);
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(ConsoleBrowserRequestFilter.class)).build();
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class ConsoleWebConfiguration {
        @Bean
        static org.springframework.validation.beanvalidation.MethodValidationPostProcessor consoleMethodValidation() {
            var validation = new org.springframework.validation.beanvalidation.MethodValidationPostProcessor();
            validation.setProxyTargetClass(true);
            return validation;
        }
    }

    @Test
    @Order(1)
    void platformLoginIssuesMinimalJwtSecureCookieAndAtomicFactsThenPublishesOutbox() throws Exception {
        TestUser user = createUser("platform-success@example.test", "Caf\u00e9-password", true, Credential.REGULAR);
        try (KafkaConsumer<String, String> consumer = kafkaConsumer()) {
            consumer.subscribe(List.of("saas.forge.test.iam-service.events"));
            consumer.poll(Duration.ofMillis(250));

            MvcResult response = login("platform-success@example.test", "Cafe\u0301-password", "PLATFORM")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"))
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900))
                    .andExpect(header().exists("Set-Cookie"))
                    .andReturn();

            JsonNode body = json(response.getResponse().getContentAsByteArray());
            String token = body.get("accessToken").asString();
            String[] segments = token.split("\\.");
            JsonNode header = json(Base64.getUrlDecoder().decode(segments[0]));
            JsonNode claims = json(Base64.getUrlDecoder().decode(segments[1]));
            assertEquals(Set.of("alg", "typ", "kid"), header.propertyNames());
            assertEquals(Set.of("iss", "aud", "iat", "exp", "identityId", "jti"), claims.propertyNames());
            assertEquals("RS256", header.get("alg").asString());
            assertEquals("JWT", header.get("typ").asString());
            assertEquals("active-login-kid", header.get("kid").asString());
            assertEquals("https://iam.test.saas.forge.invalid", claims.get("iss").asString());
            assertEquals("saas.forge-api", claims.get("aud").asString());
            assertEquals(900, claims.get("exp").asLong() - claims.get("iat").asLong());
            assertEquals(user.identity().id().toString(), claims.get("identityId").asString());
            assertEquals(7, UUID.fromString(claims.get("jti").asString()).version());

            String setCookie = response.getResponse().getHeader("Set-Cookie");
            assertNotNull(setCookie);
            assertTrue(setCookie.contains("Secure"));
            assertTrue(setCookie.contains("HttpOnly"));
            assertTrue(setCookie.contains("SameSite=Strict"));
            assertTrue(setCookie.contains("Path=/"));
            assertTrue(setCookie.contains("Max-Age=1800"));
            assertFalse(setCookie.toLowerCase().contains("domain="));
            Matcher cookieMatcher = COOKIE_VALUE.matcher(setCookie);
            assertTrue(cookieMatcher.find());
            String refreshToken = cookieMatcher.group(1);

            Map<String, Object> facts = jdbc.queryForMap("""
                    SELECT family.family_purpose, token.token_digest, issuance.kid,
                           issuance.membership_id, issuance.tenant_id
                    FROM iam_refresh_token_families family
                    JOIN iam_refresh_tokens token ON token.family_id = family.id
                    JOIN iam_access_token_issuances issuance ON issuance.family_id = family.id
                    WHERE family.identity_id = ?
                    """, user.identity().id());
            assertEquals("USER_PLATFORM", facts.get("family_purpose"));
            assertEquals("active-login-kid", facts.get("kid"));
            assertEquals(null, facts.get("membership_id"));
            assertEquals(null, facts.get("tenant_id"));
            assertArrayEquals(MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8)), (byte[]) facts.get("token_digest"));

            String snapshot = jdbc.queryForObject(
                    "SELECT event_snapshot::TEXT FROM iam_outbox_events WHERE ordering_key = ?",
                    String.class, user.identity().id().toString());
            assertNotNull(snapshot);
            assertFalse(snapshot.contains("platform-success@example.test"));
            assertFalse(snapshot.contains(refreshToken));
            assertFalse(snapshot.contains(token));

            ConsumerRecord<String, String> event = awaitEvent(consumer);
            assertEquals(user.identity().id().toString(), event.key());
            JsonNode eventJson = new ObjectMapper().readTree(event.value());
            assertEquals("com.saas.forge.iam.session.started.v1", eventJson.get("type").asString());
            assertEquals(TRACE_ID, eventJson.get("traceId").asString());
            assertEquals("USER_PLATFORM", eventJson.at("/data/purpose").asString());
            assertEquals("ACCESS_TOKEN_ISSUED", eventJson.at("/data/result").asString());
        }
    }

    @Test
    @Order(2)
    void listsOAuthClientsWithFiltersAndBoundPaginationWithoutSecrets() throws Exception {
        createUser("client-list@example.test", "correct-password", true, Credential.REGULAR);
        String token = accessToken(login("client-list@example.test", "correct-password", "PLATFORM").andReturn());
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/platform/oauth-clients")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header("Idempotency-Key", uuidV7(96_001 + i).toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                    "displayName", "list%_worker-" + i, "allowedScopes", List.of("runtime:read")))))
                    .andExpect(status().isCreated());
        }
        var first = mockMvc.perform(get("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("name", "list%_worker").param("clientType", "RUNTIME_SERVICE")
                        .param("status", "ACTIVE").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].clientType").value("RUNTIME_SERVICE"))
                .andExpect(jsonPath("$.items[0].clientSecret").doesNotExist())
                .andExpect(jsonPath("$.items[0].secretDigest").doesNotExist())
                .andExpect(jsonPath("$.hasMore").value(true)).andReturn();
        String cursor = json(first.getResponse().getContentAsByteArray()).get("nextCursor").asString();
        mockMvc.perform(get("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("name", "list%_worker").param("clientType", "RUNTIME_SERVICE")
                        .param("status", "ACTIVE").param("limit", "2").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
        mockMvc.perform(get("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("name", "list%_worker").param("status", "REVOKED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        for (String invalid : List.of("invalid", "", Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("oauth-clients:list%_worker:RUNTIME_SERVICE:ACTIVE\n2000-01-01T00:00:00Z\n"
                        + uuidV7(96_999)).getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
            mockMvc.perform(get("/api/v1/platform/oauth-clients")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .param("name", "list%_worker").param("clientType", "RUNTIME_SERVICE")
                            .param("status", "ACTIVE").param("cursor", invalid))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/platform/oauth-clients"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    void platformAdminCreatesAndReadsRuntimeOAuthClientWithOneTimeSecretAndIndependentIamAuthorization()
            throws Exception {
        TestUser admin = createUser(
                "oauth-client-admin@example.test", "correct-password", true, Credential.REGULAR);
        String platformToken = accessToken(login(
                "oauth-client-admin@example.test", "correct-password", "PLATFORM").andReturn());
        UUID key = uuidV7(66_001);
        byte[] request = new ObjectMapper().writeValueAsBytes(Map.of(
                "displayName", "reporting-worker",
                "allowedScopes", List.of("runtime:read", "runtime:quota:write")));

        MvcResult created = mockMvc.perform(post("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", key.toString())
                        .header("X-Identity-Id", uuidV7(66_099).toString())
                        .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.clientSecret").isString())
                .andReturn();

        JsonNode createdBody = json(created.getResponse().getContentAsByteArray());
        String clientSecret = createdBody.get("clientSecret").asString();
        UUID clientId = UUID.fromString(createdBody.get("clientId").asString());
        String location = created.getResponse().getHeader(HttpHeaders.LOCATION);
        assertEquals(43, clientSecret.length());
        assertEquals("/api/v1/platform/oauth-clients/" + clientId, location);

        MvcResult detail = mockMvc.perform(get(location)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientType").value("RUNTIME_SERVICE"))
                .andExpect(jsonPath("$.reservedServiceKey").doesNotExist())
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.secretId").doesNotExist())
                .andReturn();
        String detailJson = detail.getResponse().getContentAsString();
        assertFalse(detailJson.toLowerCase().contains("secret"));
        assertFalse(detailJson.toLowerCase().contains("digest"));

        Map<String, Object> persisted = jdbc.queryForMap("""
                SELECT client.client_type, client.reserved_service_key, client.created_at, client.updated_at,
                       secret.secret_digest, operation.request_fingerprint, operation.http_status,
                       event.event_snapshot::TEXT AS event_snapshot
                FROM iam_oauth_clients client
                JOIN iam_oauth_client_secrets secret ON secret.client_id = client.id
                JOIN iam_oauth_client_management_operations operation ON operation.client_id = client.id
                JOIN iam_outbox_events event ON event.ordering_key = client.id::TEXT
                WHERE client.id = ?
                """, clientId);
        assertEquals("RUNTIME_SERVICE", persisted.get("client_type"));
        assertEquals(null, persisted.get("reserved_service_key"));
        assertEquals(persisted.get("created_at"), persisted.get("updated_at"));
        assertEquals(32, ((byte[]) persisted.get("secret_digest")).length);
        assertEquals(32, ((byte[]) persisted.get("request_fingerprint")).length);
        assertEquals(201, persisted.get("http_status"));
        assertFalse(((String) persisted.get("event_snapshot")).contains(clientSecret));
        assertFalse(((String) persisted.get("event_snapshot")).toLowerCase().contains("secret"));

        mockMvc.perform(post("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_SECRET_ALREADY_REVEALED"));
        mockMvc.perform(post("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "displayName", "different", "allowedScopes", List.of("runtime:read")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mockMvc.perform(post("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", uuidV7(66_002).toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "displayName", "forbidden", "allowedScopes", List.of("iam:identity:write")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OAUTH_CLIENT_SCOPE_GRANT_FORBIDDEN"));

        mockMvc.perform(get(location).header("X-Identity-Id", admin.identity().id().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
        mockMvc.perform(get(location).header(
                        HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKENS.get().membershipReadToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));

        TestUser tenantUser = createUser(
                "oauth-client-tenant@example.test", "correct-password", false, Credential.REGULAR);
        accessibleMemberships(tenantUser.identity().id(),
                membership(uuidV7(66_010), uuidV7(66_011), "Tenant OAuth"));
        String tenantToken = accessToken(login(
                "oauth-client-tenant@example.test", "correct-password", "TENANT").andReturn());
        mockMvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_CONTEXT_REQUIRED"));

        TestUser staleRole = createUser(
                "oauth-client-stale-role@example.test", "correct-password", true, Credential.REGULAR);
        String staleRoleToken = accessToken(login(
                "oauth-client-stale-role@example.test", "correct-password", "PLATFORM").andReturn());
        jdbc.update("UPDATE iam_platform_role_assignments SET revoked_at = now() WHERE identity_id = ?",
                staleRole.identity().id());
        mockMvc.perform(get("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + staleRoleToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_ADMIN_REQUIRED"));
        mockMvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, "Bearer " + staleRoleToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_ADMIN_REQUIRED"));

        String jti = json(Base64.getUrlDecoder().decode(platformToken.split("\\.")[1]))
                .get("jti").asString();
        redis.opsForValue().set(jtiRevocationKey(UUID.fromString(jti)), "1");
        mockMvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
    }

    @Test
    @Order(3)
    void platformAdminRotatesRuntimeAndReservedSecretsWithOneTimeDeliveryAndAtomicFacts() throws Exception {
        TestUser admin = createUser(
                "oauth-rotation-admin@example.test", "correct-password", true, Credential.REGULAR);
        String platformToken = accessToken(login(
                "oauth-rotation-admin@example.test", "correct-password", "PLATFORM").andReturn());
        UUID createKey = uuidV7(67_001);
        MvcResult created = mockMvc.perform(post("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", createKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "displayName", "rotating-runtime",
                                "allowedScopes", List.of("runtime:read", "runtime:quota:write")))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createdBody = json(created.getResponse().getContentAsByteArray());
        UUID clientId = UUID.fromString(createdBody.get("clientId").asString());
        String oldSecret = createdBody.get("clientSecret").asString();
        UUID rotationKey = uuidV7(67_002);

        MvcResult rotated = rotateClient(platformToken, clientId, rotationKey)
                .andExpect(jsonPath("$.allowedScopes.length()").value(2))
                .andReturn();
        JsonNode rotatedBody = json(rotated.getResponse().getContentAsByteArray());
        String newSecret = rotatedBody.get("clientSecret").asString();
        assertEquals(43, newSecret.length());
        assertFalse(oldSecret.equals(newSecret));

        for (String validSecret : List.of(oldSecret, newSecret)) {
            mockMvc.perform(post("/oauth2/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .header(HttpHeaders.AUTHORIZATION, basic(clientId, validSecret))
                            .param("grant_type", "client_credentials")
                            .param("scope", "runtime:read"))
                    .andExpect(status().isOk());
        }

        Map<String, Object> persisted = jdbc.queryForMap("""
                SELECT client.created_at, client.updated_at,
                       count(DISTINCT secret.id) AS secret_count,
                       count(DISTINCT secret.secret_digest) AS digest_count,
                       max(operation.http_status) FILTER (WHERE operation.operation_type = 'ROTATE') AS rotate_status,
                       max(octet_length(operation.request_fingerprint))
                           FILTER (WHERE operation.operation_type = 'ROTATE') AS fingerprint_length,
                       max(event.event_snapshot::TEXT)
                           FILTER (WHERE event.event_snapshot->>'type' =
                               'com.saas.forge.iam.client-secret.rotated.v1') AS rotated_event
                FROM iam_oauth_clients client
                JOIN iam_oauth_client_secrets secret ON secret.client_id = client.id
                JOIN iam_oauth_client_management_operations operation ON operation.client_id = client.id
                JOIN iam_outbox_events event ON event.ordering_key = client.id::TEXT
                WHERE client.id = ?
                GROUP BY client.created_at, client.updated_at
                """, clientId);
        assertTrue(((java.sql.Timestamp) persisted.get("updated_at")).toInstant()
                .isAfter(((java.sql.Timestamp) persisted.get("created_at")).toInstant()));
        assertEquals(2L, persisted.get("secret_count"));
        assertEquals(2L, persisted.get("digest_count"));
        assertEquals(200, persisted.get("rotate_status"));
        assertEquals(32, persisted.get("fingerprint_length"));
        String rotatedEvent = (String) persisted.get("rotated_event");
        assertNotNull(rotatedEvent);
        assertFalse(rotatedEvent.contains(newSecret));
        assertFalse(rotatedEvent.toLowerCase().contains("digest"));
        assertFalse(rotatedEvent.contains("clientSecret"));
        assertFalse(rotatedEvent.contains("secretId"));

        mockMvc.perform(post("/api/v1/platform/oauth-clients/{clientId}/secret-rotations", clientId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", rotationKey.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_SECRET_ALREADY_REVEALED"));
        mockMvc.perform(post("/api/v1/platform/oauth-clients/{clientId}/secret-rotations", clientId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", uuidV7(67_003).toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_SECRET_ROTATION_OVERLAP_ACTIVE"));
        mockMvc.perform(post("/api/v1/platform/oauth-clients/{clientId}/secret-rotations", uuidV7(67_099))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", uuidV7(67_004).toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OAUTH_CLIENT_NOT_FOUND"));

        Instant issuedAt = Instant.now().minusSeconds(1);
        UUID tenantAccessClient = uuidV7(67_010);
        UUID entitlementClient = uuidV7(67_011);
        createReservedClientIfMissing(tenantAccessClient, "tenant-access-service", Set.of(
                OAuthScope.IAM_IDENTITY_WRITE, OAuthScope.IAM_PASSWORD_SETUP_WRITE,
                OAuthScope.IAM_PLATFORM_ROLE_READ, OAuthScope.IAM_SESSIONS_WRITE,
                OAuthScope.ENTITLEMENT_QUOTA_WRITE), (byte) 67, issuedAt);
        createReservedClientIfMissing(entitlementClient, "entitlement-service", Set.of(
                OAuthScope.TENANT_ACCESS_TENANT_READ, OAuthScope.IAM_PLATFORM_ROLE_READ), (byte) 68, issuedAt);
        Set<String> representedScopes = new java.util.HashSet<>();
        representedScopes.addAll(scopeValues(rotatedBody));
        representedScopes.addAll(scopeValues(json(rotateClient(
                platformToken, IAM_SERVICE_CLIENT_ID, uuidV7(67_012)).andReturn()
                .getResponse().getContentAsByteArray())));
        representedScopes.addAll(scopeValues(json(rotateClient(
                platformToken, tenantAccessClient, uuidV7(67_013)).andReturn()
                .getResponse().getContentAsByteArray())));
        representedScopes.addAll(scopeValues(json(rotateClient(
                platformToken, entitlementClient, uuidV7(67_014)).andReturn()
                .getResponse().getContentAsByteArray())));
        assertEquals(Set.of(
                "runtime:read", "runtime:quota:write", "tenant-access:membership:read",
                "iam:identity:write", "iam:password-setup:write", "iam:platform-role:read",
                "iam:sessions:write", "entitlement:quota:write", "tenant-access:tenant:read"),
                representedScopes);

        UUID revokedClient = uuidV7(67_020);
        oauthClients.createWithId(
                OAuthClient.register("revoked-rotation", Set.of(OAuthScope.RUNTIME_READ), issuedAt)
                        .identifiedBy(revokedClient),
                ClientSecretDigest.fromPlaintext(serviceClientSecret((byte) 69)), issuedAt);
        oauthClients.revoke(revokedClient, Instant.now());
        mockMvc.perform(post("/api/v1/platform/oauth-clients/{clientId}/secret-rotations", revokedClient)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", uuidV7(67_021).toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OAUTH_CLIENT_REVOKED"));
    }

    @Test
    @Order(4)
    void platformAdminRecoversLostRotationOnceAndPreservesTheStableSecretDeadline() throws Exception {
        createUser("oauth-recovery-admin@example.test", "correct-password", true, Credential.REGULAR);
        String platformToken = accessToken(login(
                "oauth-recovery-admin@example.test", "correct-password", "PLATFORM").andReturn());
        UUID createKey = uuidV7(69_001);
        MvcResult created = mockMvc.perform(post("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", createKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "displayName", "recovering-runtime",
                                "allowedScopes", List.of("runtime:read")))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createdBody = json(created.getResponse().getContentAsByteArray());
        UUID clientId = UUID.fromString(createdBody.get("clientId").asString());
        String stableSecret = createdBody.get("clientSecret").asString();

        mockMvc.perform(post("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", createKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "displayName", "recovering-runtime",
                                "allowedScopes", List.of("runtime:read")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_SECRET_ALREADY_REVEALED"))
                .andExpect(jsonPath("$.clientId").value(clientId.toString()));

        UUID rotationKey = uuidV7(69_002);
        JsonNode rotatedBody = json(rotateClient(platformToken, clientId, rotationKey)
                .andReturn().getResponse().getContentAsByteArray());
        String lostRotatedSecret = rotatedBody.get("clientSecret").asString();
        java.sql.Timestamp stableDeadline = jdbc.queryForObject(
                "SELECT valid_until FROM iam_oauth_client_secrets WHERE client_id = ? AND valid_until IS NOT NULL",
                java.sql.Timestamp.class, clientId);

        UUID recoveryKey = uuidV7(69_003);
        byte[] recoveryRequest = new ObjectMapper().writeValueAsBytes(Map.of(
                "originalIdempotencyKey", rotationKey));
        MvcResult recovered = mockMvc.perform(post(
                        "/api/v1/platform/oauth-clients/{clientId}/secret-issuance-recoveries", clientId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", recoveryKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recoveryRequest))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.clientSecret").isString())
                .andReturn();
        String replacementSecret = json(recovered.getResponse().getContentAsByteArray())
                .get("clientSecret").asString();
        assertFalse(lostRotatedSecret.equals(replacementSecret));
        assertEquals(stableDeadline, jdbc.queryForObject(
                "SELECT valid_until FROM iam_oauth_client_secrets WHERE client_id = ? AND valid_until IS NOT NULL",
                java.sql.Timestamp.class, clientId));

        for (String validSecret : List.of(stableSecret, replacementSecret)) {
            mockMvc.perform(post("/oauth2/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .header(HttpHeaders.AUTHORIZATION, basic(clientId, validSecret))
                            .param("grant_type", "client_credentials"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(clientId, lostRotatedSecret))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(
                        "/api/v1/platform/oauth-clients/{clientId}/secret-issuance-recoveries", clientId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", recoveryKey.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(recoveryRequest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_SECRET_RECOVERY_NOT_ALLOWED"));
        mockMvc.perform(post(
                        "/api/v1/platform/oauth-clients/{clientId}/secret-issuance-recoveries", clientId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", uuidV7(69_004).toString())
                        .contentType(MediaType.APPLICATION_JSON).content(recoveryRequest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENT_SECRET_RECOVERY_NOT_ALLOWED"));

        Map<String, Object> terminal = jdbc.queryForMap("""
                SELECT recovery.original_operation_id, recovery.secret_record_id,
                       original.secret_record_id AS original_secret_record_id,
                       event.event_snapshot::TEXT AS event_snapshot
                FROM iam_oauth_client_management_operations recovery
                JOIN iam_oauth_client_management_operations original ON original.id = recovery.original_operation_id
                JOIN iam_outbox_events event ON event.ordering_key = recovery.client_id::TEXT
                    AND event.event_snapshot->>'type' =
                        'com.saas.forge.iam.client-secret.issuance-recovered.v1'
                WHERE recovery.actor_identity_id = ? AND recovery.idempotency_key = ?
                """, UUID.fromString(json(Base64.getUrlDecoder().decode(platformToken.split("\\.")[1]))
                .get("identityId").asString()), recoveryKey);
        assertNotNull(terminal.get("original_operation_id"));
        assertNotNull(terminal.get("secret_record_id"));
        assertNotNull(terminal.get("original_secret_record_id"));
        String event = (String) terminal.get("event_snapshot");
        assertTrue(event.contains("CLIENT_SECRET_ISSUANCE_RECOVERED"));
        assertFalse(event.contains(replacementSecret));
        assertFalse(event.toLowerCase().contains("digest"));
        assertFalse(event.contains("secretRecordId"));
    }

    @Test
    @Order(4)
    void clientRevocationIsRedisFirstIdempotentAndRecoveredFromPostgresql() throws Exception {
        TestUser admin = createUser(
                "oauth-revocation-admin@example.test", "correct-password", true, Credential.REGULAR);
        String platformToken = accessToken(login(
                "oauth-revocation-admin@example.test", "correct-password", "PLATFORM").andReturn());
        UUID createKey = uuidV7(68_001);
        MvcResult created = mockMvc.perform(post("/api/v1/platform/oauth-clients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", createKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "displayName", "revocable-runtime",
                                "allowedScopes", List.of("runtime:read")))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json(created.getResponse().getContentAsByteArray());
        UUID clientId = UUID.fromString(body.get("clientId").asString());
        String secret = body.get("clientSecret").asString();

        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk());

        // 复用创建键会让数据库事务失败，但 Redis 额外拒绝必须保留且签发失败关闭。
        mockMvc.perform(post("/api/v1/platform/oauth-clients/{clientId}/revocations", clientId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", createKey.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        String clientKey = oauthClientRevocationKey(clientId);
        assertEquals("1", redis.opsForValue().get(clientKey));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT client_status FROM iam_oauth_clients WHERE id = ?", String.class, clientId));
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOCATION_STATUS_UNAVAILABLE"));

        UUID revokeKey = uuidV7(68_002);
        mockMvc.perform(post("/api/v1/platform/oauth-clients/{clientId}/revocations", clientId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", revokeKey.toString())
                        .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01"))
                .andExpect(status().isNoContent());
        assertEquals(-1L, redis.getExpire(clientKey, TimeUnit.MILLISECONDS));
        Map<String, Object> first = jdbc.queryForMap("""
                SELECT client.revoked_at, client.updated_at,
                       count(DISTINCT secret.id) FILTER (WHERE secret.revoked_at IS NOT NULL) AS revoked_secrets,
                       count(DISTINCT operation.id) FILTER (WHERE operation.operation_type = 'REVOKE') AS operations,
                       count(DISTINCT event.event_id) FILTER (WHERE event.event_snapshot->>'type' =
                           'com.saas.forge.iam.oauth-client.revoked.v1') AS events
                FROM iam_oauth_clients client
                JOIN iam_oauth_client_secrets secret ON secret.client_id = client.id
                LEFT JOIN iam_oauth_client_management_operations operation ON operation.client_id = client.id
                LEFT JOIN iam_outbox_events event ON event.ordering_key = client.id::TEXT
                WHERE client.id = ?
                GROUP BY client.revoked_at, client.updated_at
                """, clientId);
        assertNotNull(first.get("revoked_at"));
        assertEquals(first.get("revoked_at"), first.get("updated_at"));
        assertEquals(1L, first.get("revoked_secrets"));
        assertEquals(1L, first.get("operations"));
        assertEquals(1L, first.get("events"));

        TestUser otherAdmin = createUser(
                "oauth-revocation-other@example.test", "correct-password", true, Credential.REGULAR);
        String otherToken = accessToken(login(
                "oauth-revocation-other@example.test", "correct-password", "PLATFORM").andReturn());
        mockMvc.perform(post("/api/v1/platform/oauth-clients/{clientId}/revocations", clientId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                        .header("Idempotency-Key", uuidV7(68_003).toString()))
                .andExpect(status().isNoContent());
        assertEquals(first.get("revoked_at"), jdbc.queryForObject(
                "SELECT revoked_at FROM iam_oauth_clients WHERE id = ?", java.sql.Timestamp.class, clientId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM iam_oauth_client_management_operations "
                        + "WHERE client_id = ? AND operation_type = 'REVOKE'", Long.class, clientId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events WHERE ordering_key = ? "
                        + "AND event_snapshot->>'type' = 'com.saas.forge.iam.oauth-client.revoked.v1'",
                Long.class, clientId.toString()));

        UUID missing = uuidV7(68_099);
        mockMvc.perform(post("/api/v1/platform/oauth-clients/{clientId}/revocations", missing)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", uuidV7(68_004).toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OAUTH_CLIENT_NOT_FOUND"));
        assertEquals(null, redis.opsForValue().get(oauthClientRevocationKey(missing)));

        UUID activeClient = uuidV7(68_010);
        String activeSecret = serviceClientSecret((byte) 70);
        oauthClients.createWithId(
                OAuthClient.register("rebuild-active", Set.of(OAuthScope.RUNTIME_READ), Instant.now())
                        .identifiedBy(activeClient),
                ClientSecretDigest.fromPlaintext(activeSecret), Instant.now());
        redis.opsForValue().set(oauthClientRevocationKey(activeClient), "1");
        revocationIndex.markNotReady();
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(activeClient, activeSecret))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOCATION_STATUS_UNAVAILABLE"));
        revocationIndexRecovery.recover();
        assertEquals(null, redis.opsForValue().get(oauthClientRevocationKey(activeClient)));
        assertEquals("1", redis.opsForValue().get(clientKey));
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(activeClient, activeSecret))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(3)
    void credentialFailuresAreUniformAndFifthFailureLocksWithoutCreatingSessionFacts() throws Exception {
        TestUser wrongPassword = createUser("wrong-password@example.test", "correct-password", true, Credential.REGULAR);
        TestUser noCredential = createUser("no-credential@example.test", "unused", true, Credential.NONE);
        TestUser expiredInitial = createUser("expired-initial@example.test", "initial-password", true, Credential.EXPIRED_INITIAL);
        TestUser locked = createUser("locked@example.test", "correct-password", true, Credential.REGULAR);

        assertAuthenticationFailed(login("unknown@example.test", "any-password", "PLATFORM").andReturn());
        assertAuthenticationFailed(login("wrong-password@example.test", "wrong", "PLATFORM").andReturn());
        assertAuthenticationFailed(login("no-credential@example.test", "unused", "PLATFORM").andReturn());
        assertAuthenticationFailed(login("expired-initial@example.test", "initial-password", "PLATFORM").andReturn());
        for (int attempt = 0; attempt < 5; attempt++) {
            assertAuthenticationFailed(login("locked@example.test", "wrong", "PLATFORM").andReturn());
        }
        assertAuthenticationFailed(login("locked@example.test", "correct-password", "PLATFORM").andReturn());

        for (TestUser user : List.of(wrongPassword, noCredential, expiredInitial, locked)) {
            assertEquals(0, sessionFactCount(user.identity().id()));
        }
        assertEquals(1, redis.keys("sf:test:iam-service:login-lock:v1:*").size());
    }

    @Test
    @Order(3)
    void successfulPasswordClearsFailuresAndTenantDefaultCannotBorrowPlatformRole() throws Exception {
        TestUser user = createUser("default-tenant@example.test", "correct-password", true, Credential.REGULAR);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertAuthenticationFailed(login("default-tenant@example.test", "wrong", "PLATFORM").andReturn());
        }

        MvcResult tenantResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://console.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"default-tenant@example.test","password":"correct-password"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        assertNotNull(tenantResponse);
        assertEquals(0, sessionFactCount(user.identity().id()));
        assertTrue(redis.keys("sf:test:iam-service:login-failure:v1:*").isEmpty());
        assertTrue(redis.keys("sf:test:iam-service:login-lock:v1:*").isEmpty());
    }

    @Test
    @Order(4)
    void platformRoleIsRequiredAfterPasswordVerification() throws Exception {
        TestUser user = createUser("no-role@example.test", "correct-password", false, Credential.REGULAR);
        login("no-role@example.test", "correct-password", "PLATFORM")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(0, sessionFactCount(user.identity().id()));
    }

    @Test
    @Order(5)
    void readsCurrentPlatformSessionFromAuthoritativeIdentityAndAuthorization() throws Exception {
        TestUser user = createUser("current-platform@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult login = login("current-platform@example.test", "correct-password", "PLATFORM")
                .andExpect(status().isOk()).andReturn();
        String bearer = "Bearer " + accessToken(login);
        mockMvc.perform(get("/api/v1/auth/session").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.identityId").value(user.identity().id().toString()))
                .andExpect(jsonPath("$.email").value("current-platform@example.test"))
                .andExpect(jsonPath("$.platformAdmin").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.expiresIn").doesNotExist());
        // 读取成功并不冻结授权；业务操作必须再次检查当前 Role。
        jdbc.update("UPDATE iam_platform_role_assignments SET revoked_at = now() WHERE identity_id = ?",
                user.identity().id());
        mockMvc.perform(get("/api/v1/auth/session").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.platformAdmin").value(false));
        mockMvc.perform(get("/api/v1/platform/oauth-clients/{clientId}", uuidV7(69_001))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void currentSessionRejectsWrongCredentialsAndTenantContextWithoutChangingCookies() throws Exception {
        mockMvc.perform(get("/api/v1/auth/session")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer malformed"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/session")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKENS.get().membershipReadToken()))
                .andExpect(status().isUnauthorized());
        TestUser tenant = createUser("current-tenant@example.test", "correct-password", true, Credential.REGULAR);
        accessibleMemberships(tenant.identity().id(), membership(uuidV7(69_011), uuidV7(69_012), "Tenant"));
        MvcResult login = login("current-tenant@example.test", "correct-password", "TENANT")
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(get("/api/v1/auth/session")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(login)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mockMvc.perform(get("/api/v1/auth/session")
                        .cookie(new Cookie("__Host-sf_tenant_refresh", refreshToken(login))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    void currentSessionDoesNotPromiseRefreshAndFailsClosedForRevocation() throws Exception {
        TestUser user = createUser("current-revocation@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult login = login("current-revocation@example.test", "correct-password", "PLATFORM")
                .andExpect(status().isOk()).andReturn();
        String token = accessToken(login);
        // 仅终止 Refresh Family，保留短期 Access Token，证明读取不是未来刷新的保证。
        jdbc.update("UPDATE iam_refresh_token_families SET revoked_at = now() WHERE identity_id = ?",
                user.identity().id());
        mockMvc.perform(get("/api/v1/auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        refresh(refreshToken(login), UUID.randomUUID()).andExpect(status().isUnauthorized());
        revocationIndex.markNotReady();
        try {
            mockMvc.perform(get("/api/v1/auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("TOKEN_REVOCATION_STATUS_UNAVAILABLE"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        } finally {
            revocationIndexRecovery.recover();
        }
    }

    @Test
    @Order(5)
    void readsCurrentTenantContextWithBearerWithoutRotatingTheSession() throws Exception {
        TestUser user = createUser("context-reader@example.test", "correct-password", false, Credential.REGULAR);
        UUID membershipId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4d90");
        UUID tenantId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4d91");
        accessibleMemberships(user.identity().id(), membership(membershipId, tenantId, "当前租户"));
        tenantBrandProfile(tenantId, "当前品牌", "/logo.svg", "/icon.svg", "#155EEF", "#7A5AF8");
        MvcResult login = login("context-reader@example.test", "correct-password", "TENANT")
                .andExpect(status().isOk()).andReturn();

        mockMvc.perform(get("/api/v1/auth/context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(login))
                        .param("tenantId", "0198c9d5-0f25-7b21-8d67-31c8652d4d99"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.membershipId").value(membershipId.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.brandProfile.displayName").value("当前品牌"))
                .andExpect(jsonPath("$.accessibleMemberships.length()").value(1))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        // 原 Cookie 仍可刷新，读取 Context 不消费 Refresh Token。
        refresh(refreshToken(login), UUID.randomUUID()).andExpect(status().isOk());
    }

    @Test
    @Order(5)
    void currentTenantContextRejectsARevokedAccessToken() throws Exception {
        TestUser user = createUser("context-revoked@example.test", "correct-password", false, Credential.REGULAR);
        accessibleMemberships(user.identity().id(), membership(
                UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4e90"),
                UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4e91"), "已退出租户"));
        MvcResult login = login("context-revoked@example.test", "correct-password", "TENANT")
                .andExpect(status().isOk()).andReturn();
        logout(refreshToken(login), accessToken(login)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(login)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @Order(5)
    void currentTenantContextFailsClosedWithoutChangingCookiesWhenRevocationIsUnavailable() throws Exception {
        TestUser user = createUser("context-unavailable@example.test", "correct-password", false, Credential.REGULAR);
        accessibleMemberships(user.identity().id(), membership(
                UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4f90"),
                UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4f91"), "待确认租户"));
        MvcResult login = login("context-unavailable@example.test", "correct-password", "TENANT")
                .andExpect(status().isOk()).andReturn();
        revocationIndex.markNotReady();
        try {
            mockMvc.perform(get("/api/v1/auth/context")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(login)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("TOKEN_REVOCATION_STATUS_UNAVAILABLE"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        } finally {
            revocationIndexRecovery.recover();
        }
    }

    @Test
    @Order(5)
    void currentTenantContextRejectsAFencedMembership() throws Exception {
        TestUser user = createUser("context-fenced@example.test", "correct-password", false, Credential.REGULAR);
        UUID membershipId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4090");
        UUID tenantId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4091");
        accessibleMemberships(user.identity().id(), membership(membershipId, tenantId, "受限租户"));
        MvcResult login = login("context-fenced@example.test", "correct-password", "TENANT")
                .andExpect(status().isOk()).andReturn();
        RevocationFence fence = RevocationFence.establish(
                UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4092"),
                RevocationFenceTarget.membership(membershipId, tenantId), Instant.now());
        revocationIndex.establishFence(fence);
        try {
            mockMvc.perform(get("/api/v1/auth/context")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(login)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        } finally {
            revocationIndex.releaseFence(fence);
        }
    }

    @Test
    @Order(5)
    void currentTenantContextRequiresTenantBearerRatherThanCookieOrPlatformCredentials() throws Exception {
        createUser("context-platform@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult platform = login("context-platform@example.test", "correct-password", "PLATFORM")
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(get("/api/v1/auth/context")
                        .cookie(new Cookie("__Host-sf_platform_refresh", refreshToken(platform))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"));
        mockMvc.perform(get("/api/v1/auth/context").header(HttpHeaders.AUTHORIZATION, "Bearer malformed"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKENS.get().membershipReadToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(platform)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"));
    }

    @Test
    @Order(5)
    void defaultTenantLoginWithOneMembershipIssuesPairedClaimsAndTenantSession() throws Exception {
        TestUser user = createUser("single-tenant@example.test", "correct-password", false, Credential.REGULAR);
        UUID membershipId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90");
        UUID tenantId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c91");
        accessibleMemberships(user.identity().id(), membership(membershipId, tenantId, "唯一租户"));
        tenantBrandProfile(
                tenantId,
                "唯一品牌",
                "/brands/single-logo.svg",
                "/brands/single-favicon.svg",
                "#155EEF",
                "#7A5AF8");

        MvcResult response = loginWithoutContext("single-tenant@example.test", "correct-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"))
                .andExpect(jsonPath("$.tenantContext.membershipId").value(membershipId.toString()))
                .andExpect(jsonPath("$.tenantContext.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.tenantContext.tenantDisplayName").value("唯一租户"))
                .andExpect(jsonPath("$.tenantContext.accessibleMemberships.length()").value(1))
                .andExpect(jsonPath("$.tenantContext.accessibleMemberships[0].membershipId")
                        .value(membershipId.toString()))
                .andExpect(jsonPath("$.tenantContext.brandProfile.displayName").value("唯一品牌"))
                .andExpect(jsonPath("$.tenantContext.brandProfile.logoUrl")
                        .value("/brands/single-logo.svg"))
                .andExpect(jsonPath("$.tenantContext.brandProfile.faviconUrl")
                        .value("/brands/single-favicon.svg"))
                .andExpect(jsonPath("$.tenantContext.brandProfile.primaryColor").value("#155EEF"))
                .andExpect(jsonPath("$.tenantContext.brandProfile.accentColor").value("#7A5AF8"))
                .andExpect(header().exists("Set-Cookie"))
                .andReturn();

        JsonNode claims = tokenClaims(response);
        assertEquals(Set.of("iss", "aud", "iat", "exp", "identityId", "membershipId", "tenantId", "jti"),
                claims.propertyNames());
        assertEquals(membershipId.toString(), claims.get("membershipId").asString());
        assertEquals(tenantId.toString(), claims.get("tenantId").asString());
        Map<String, Object> facts = jdbc.queryForMap("""
                SELECT family.family_purpose, family.membership_id, family.tenant_id,
                       issuance.membership_id AS issuance_membership_id,
                       issuance.tenant_id AS issuance_tenant_id
                FROM iam_refresh_token_families family
                JOIN iam_access_token_issuances issuance ON issuance.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertEquals("USER_TENANT", facts.get("family_purpose"));
        assertEquals(membershipId, facts.get("membership_id"));
        assertEquals(tenantId, facts.get("tenant_id"));
        assertEquals(membershipId, facts.get("issuance_membership_id"));
        assertEquals(tenantId, facts.get("issuance_tenant_id"));
    }

    @Test
    @Order(6)
    void multipleMembershipsCreateOnlySelectionSessionAndReturnStableCandidates() throws Exception {
        TestUser user = createUser("multi-tenant@example.test", "correct-password", true, Credential.REGULAR);
        UUID alphaMembership = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4ca0");
        UUID betaMembership = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4ca1");
        accessibleMemberships(user.identity().id(),
                membership(betaMembership, UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4cb1"), "Beta"),
                membership(alphaMembership, UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4cb0"), "Alpha"));

        login("multi-tenant@example.test", "correct-password", "TENANT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("CONTEXT_SELECTION_REQUIRED"))
                .andExpect(jsonPath("$.memberships.length()").value(2))
                .andExpect(jsonPath("$.memberships[0].membershipId").value(alphaMembership.toString()))
                .andExpect(jsonPath("$.memberships[1].membershipId").value(betaMembership.toString()))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(header().exists("Set-Cookie"));

        assertEquals("USER_TENANT_SELECTION", jdbc.queryForObject(
                "SELECT family_purpose FROM iam_refresh_token_families WHERE identity_id = ?",
                String.class, user.identity().id()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM iam_access_token_issuances issuance
                JOIN iam_refresh_token_families family ON family.id = issuance.family_id
                WHERE family.identity_id = ?
                """, Integer.class, user.identity().id()));
        String event = jdbc.queryForObject(
                "SELECT event_snapshot::TEXT FROM iam_outbox_events WHERE ordering_key = ?",
                String.class, user.identity().id().toString());
        assertTrue(event.contains("\"purpose\": \"USER_TENANT_SELECTION\""));
        assertTrue(event.contains("\"contextType\": \"TENANT\""));
        assertTrue(event.contains("\"result\": \"CONTEXT_SELECTION_REQUIRED\""));
    }

    @Test
    @Order(7)
    void overOneHundredMembershipsRejectWithoutAuthenticationState() throws Exception {
        TestUser user = createUser("membership-overflow@example.test", "correct-password", false, Credential.REGULAR);
        List<io.saas.forge.contracts.tenantaccess.membership.v1.AccessibleMembership> memberships =
                java.util.stream.IntStream.range(0, 101)
                        .mapToObj(index -> membership(
                                uuidV7(10_000 + index), uuidV7(20_000 + index), "Tenant " + index))
                        .toList();
        accessibleMemberships(user.identity().id(), memberships.toArray(
                io.saas.forge.contracts.tenantaccess.membership.v1.AccessibleMembership[]::new));

        login("membership-overflow@example.test", "correct-password", "TENANT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCESSIBLE_MEMBERSHIP_LIMIT_EXCEEDED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(0, sessionFactCount(user.identity().id()));
    }

    @Test
    @Order(8)
    void tenantAccessFailureIsRetryableAndCreatesNoAuthenticationState() throws Exception {
        TestUser user = createUser("tenant-access-down@example.test", "correct-password", false, Credential.REGULAR);
        TENANT_ACCESS_FAILURES.add(user.identity().id());

        login("tenant-access-down@example.test", "correct-password", "TENANT")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(0, sessionFactCount(user.identity().id()));
    }

    @Test
    @Order(9)
    void loginRequestRejectsCallerSuppliedTenantOrMembershipIdentifiers() throws Exception {
        TestUser user = createUser("forged-context@example.test", "correct-password", false, Credential.REGULAR);
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://console.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"forged-context@example.test","password":"correct-password",
                                 "tenantId":"0198c9d5-0f25-7b21-8d67-31c8652d4cc0"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Set-Cookie"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://console.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"forged-context@example.test","password":"correct-password",
                                 "membershipId":"0198c9d5-0f25-7b21-8d67-31c8652d4cc1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(0, sessionFactCount(user.identity().id()));
    }

    @Test
    @Order(10)
    void contextSelectionRevalidatesMembershipThenAtomicallyRotatesIntoTenantSession() throws Exception {
        TestUser user = createUser("selection-success@example.test", "correct-password", false, Credential.REGULAR);
        UUID selectedMembership = uuidV7(30_001);
        UUID selectedTenant = uuidV7(30_002);
        UUID otherMembership = uuidV7(30_003);
        accessibleMemberships(user.identity().id(),
                membership(selectedMembership, selectedTenant, "Alpha"),
                membership(otherMembership, uuidV7(30_004), "Beta"));
        MvcResult pending = login("selection-success@example.test", "correct-password", "TENANT").andReturn();
        String selectionToken = refreshToken(pending);

        MvcResult selected = selectContext(selectionToken, selectedMembership)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.tenantContext.membershipId").value(selectedMembership.toString()))
                .andExpect(jsonPath("$.tenantContext.tenantId").value(selectedTenant.toString()))
                .andExpect(jsonPath("$.tenantContext.accessibleMemberships.length()").value(2))
                .andExpect(header().exists("Set-Cookie"))
                .andReturn();

        JsonNode claims = tokenClaims(selected);
        assertEquals(selectedMembership.toString(), claims.get("membershipId").asString());
        assertEquals(selectedTenant.toString(), claims.get("tenantId").asString());
        String rotatedToken = refreshToken(selected);
        assertFalse(selectionToken.equals(rotatedToken));
        Map<String, Object> family = jdbc.queryForMap("""
                SELECT family_purpose, membership_id, tenant_id, revoked_at
                FROM iam_refresh_token_families WHERE identity_id = ?
                """, user.identity().id());
        assertEquals("USER_TENANT", family.get("family_purpose"));
        assertEquals(selectedMembership, family.get("membership_id"));
        assertEquals(selectedTenant, family.get("tenant_id"));
        assertEquals(null, family.get("revoked_at"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_tokens token
                JOIN iam_refresh_token_families family ON family.id = token.family_id
                WHERE family.identity_id = ? AND token.consumed_at IS NOT NULL
                """, Integer.class, user.identity().id()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_tokens token
                JOIN iam_refresh_token_families family ON family.id = token.family_id
                WHERE family.identity_id = ? AND token.consumed_at IS NULL
                """, Integer.class, user.identity().id()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_access_token_issuances issuance
                JOIN iam_refresh_token_families family ON family.id = issuance.family_id
                WHERE family.identity_id = ?
                """, Integer.class, user.identity().id()));

        selectContext(selectionToken, selectedMembership)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CONTEXT_SELECTION_SESSION_INVALID"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_access_token_issuances issuance
                JOIN iam_refresh_token_families family ON family.id = issuance.family_id
                WHERE family.identity_id = ?
                """, Integer.class, user.identity().id()));
    }

    @Test
    @Order(11)
    void inaccessibleSelectionIsRejectedAndConsumesTheSelectionSession() throws Exception {
        TestUser user = createUser("selection-rejected@example.test", "correct-password", false, Credential.REGULAR);
        UUID staleMembership = uuidV7(31_001);
        UUID currentMembership = uuidV7(31_002);
        accessibleMemberships(user.identity().id(),
                membership(staleMembership, uuidV7(31_003), "Alpha"),
                membership(currentMembership, uuidV7(31_004), "Beta"));
        String selectionToken = refreshToken(
                login("selection-rejected@example.test", "correct-password", "TENANT").andReturn());
        accessibleMemberships(user.identity().id(), membership(currentMembership, uuidV7(31_004), "Beta"));

        selectContext(selectionToken, staleMembership)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONTEXT_SELECTION_REJECTED"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        Map<String, Object> facts = jdbc.queryForMap("""
                SELECT family.revoked_at, token.consumed_at
                FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertNotNull(facts.get("revoked_at"));
        assertNotNull(facts.get("consumed_at"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM iam_access_token_issuances issuance
                JOIN iam_refresh_token_families family ON family.id = issuance.family_id
                WHERE family.identity_id = ?
                """, Integer.class, user.identity().id()));
    }

    @Test
    @Order(12)
    void tenantAccessFailureDoesNotConsumeSelectionAndTheSameCookieCanRetry() throws Exception {
        TestUser user = createUser("selection-retry@example.test", "correct-password", false, Credential.REGULAR);
        UUID selectedMembership = uuidV7(32_001);
        UUID selectedTenant = uuidV7(32_002);
        accessibleMemberships(user.identity().id(),
                membership(selectedMembership, selectedTenant, "Alpha"),
                membership(uuidV7(32_003), uuidV7(32_004), "Beta"));
        String selectionToken = refreshToken(
                login("selection-retry@example.test", "correct-password", "TENANT").andReturn());
        TENANT_ACCESS_FAILURES.add(user.identity().id());

        selectContext(selectionToken, selectedMembership)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        Map<String, Object> pendingFacts = jdbc.queryForMap("""
                SELECT family.family_purpose, family.revoked_at, token.consumed_at
                FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertEquals("USER_TENANT_SELECTION", pendingFacts.get("family_purpose"));
        assertEquals(null, pendingFacts.get("revoked_at"));
        assertEquals(null, pendingFacts.get("consumed_at"));

        TENANT_ACCESS_FAILURES.remove(user.identity().id());
        selectContext(selectionToken, selectedMembership).andExpect(status().isOk());
    }

    @Test
    @Order(13)
    void expiredOrWrongPurposeCookieCannotCreateTenantSession() throws Exception {
        TestUser expired = createUser("selection-expired@example.test", "correct-password", false, Credential.REGULAR);
        UUID membershipId = uuidV7(33_001);
        accessibleMemberships(expired.identity().id(),
                membership(membershipId, uuidV7(33_002), "Alpha"),
                membership(uuidV7(33_003), uuidV7(33_004), "Beta"));
        String expiredToken = refreshToken(
                login("selection-expired@example.test", "correct-password", "TENANT").andReturn());
        jdbc.update("UPDATE iam_refresh_token_families SET last_used_at = now() - interval '31 minutes' WHERE identity_id = ?",
                expired.identity().id());
        selectContext(expiredToken, membershipId)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CONTEXT_SELECTION_SESSION_INVALID"));

        TestUser tenant = createUser("selection-purpose@example.test", "correct-password", false, Credential.REGULAR);
        accessibleMemberships(tenant.identity().id(), membership(membershipId, uuidV7(33_002), "Alpha"));
        String tenantToken = refreshToken(
                login("selection-purpose@example.test", "correct-password", "TENANT").andReturn());
        selectContext(tenantToken, membershipId)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CONTEXT_SELECTION_SESSION_INVALID"));
        assertEquals("USER_TENANT", jdbc.queryForObject(
                "SELECT family_purpose FROM iam_refresh_token_families WHERE identity_id = ?",
                String.class, tenant.identity().id()));
    }

    @Test
    @Order(14)
    void contextSelectionBodyRejectsFieldsOtherThanMembershipId() throws Exception {
        mockMvc.perform(post("/api/v1/auth/context-selections")
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://console.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .cookie(new Cookie("__Host-sf_tenant_refresh", "not-a-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"membershipId":"0198c9d5-0f25-7b21-8d67-31c8652d4cc1",
                                 "tenantId":"0198c9d5-0f25-7b21-8d67-31c8652d4cc2"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(15)
    void initialCredentialTakesPriorityAndPasswordChangeCommitsAllFactsAtomically() throws Exception {
        TestUser user = createUser(
                "initial-change@example.test", "Initial-Credential-2026!", true, Credential.ACTIVE_INITIAL);
        TENANT_ACCESS_FAILURES.add(user.identity().id());

        login("initial-change@example.test", "Initial-Credential-2026!", "TENANT")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BROWSER_REQUEST_REJECTED"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        MvcResult login = login("initial-change@example.test", "Initial-Credential-2026!", "PLATFORM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.memberships").doesNotExist())
                .andReturn();
        Cookie refreshCookie = login.getResponse().getCookie("__Host-sf_platform_refresh");
        assertNotNull(refreshCookie);
        assertTrue(refreshCookie.getMaxAge() > 0);
        assertTrue(refreshCookie.getMaxAge() <= Duration.ofMinutes(10).getSeconds());
        String refreshToken = refreshToken(login);

        Map<String, Object> initialFacts = jdbc.queryForMap("""
                SELECT family.family_purpose, family.initial_credential_id, family.absolute_expires_at,
                       token.consumed_at, family.revoked_at
                FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertEquals("INITIAL_PASSWORD_CHANGE", initialFacts.get("family_purpose"));
        assertNotNull(initialFacts.get("initial_credential_id"));
        assertEquals(null, initialFacts.get("consumed_at"));
        assertEquals(null, initialFacts.get("revoked_at"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM iam_access_token_issuances WHERE identity_id = ?",
                Integer.class, user.identity().id()));

        changePassword(refreshToken, "saas.forge2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_COMPROMISED"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM iam_credentials WHERE identity_id = ? AND credential_type = 'PASSWORD'",
                Integer.class, user.identity().id()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_tokens token
                JOIN iam_refresh_token_families family ON family.id = token.family_id
                WHERE family.identity_id = ? AND token.consumed_at IS NOT NULL
                """, Integer.class, user.identity().id()));

        changePassword(refreshToken, "Unique-Credential-2026!")
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        Map<String, Object> completed = jdbc.queryForMap("""
                SELECT family.revoked_at, token.consumed_at,
                       (SELECT count(*) FROM iam_credentials credential
                        WHERE credential.identity_id = family.identity_id
                          AND credential.credential_type = 'PASSWORD' AND credential.invalidated_at IS NULL) AS regular_count,
                       (SELECT count(*) FROM iam_credentials credential
                        WHERE credential.id = family.initial_credential_id
                          AND credential.invalidated_at IS NOT NULL) AS invalidated_initial_count
                FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertNotNull(completed.get("revoked_at"));
        assertNotNull(completed.get("consumed_at"));
        assertEquals(1L, completed.get("regular_count"));
        assertEquals(1L, completed.get("invalidated_initial_count"));

        JsonNode event = json(jdbc.queryForObject("""
                SELECT event_snapshot::TEXT FROM iam_outbox_events
                WHERE ordering_key = ? AND event_snapshot ->> 'type' = 'com.saas.forge.iam.password.changed.v1'
                """, String.class, user.identity().id().toString()).getBytes(StandardCharsets.UTF_8));
        assertEquals("com.saas.forge.iam.password.changed.v1", event.get("type").asString());
        assertFalse(event.toString().contains("Unique-Credential-2026!"));

        assertAuthenticationFailed(login("initial-change@example.test", "Initial-Credential-2026!", "PLATFORM")
                .andReturn());
        login("initial-change@example.test", "Unique-Credential-2026!", "PLATFORM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"));
    }

    @Test
    @Order(16)
    void passwordChangePublicContractCoversPasswordBoundariesWithoutConsumingSession() throws Exception {
        TestUser user = createUser(
                "password-boundaries@example.test", "Initial-Boundaries-2026!", false, Credential.ACTIVE_INITIAL);
        String refreshToken = refreshToken(login(
                "password-boundaries@example.test", "Initial-Boundaries-2026!", "PLATFORM").andReturn());

        changePassword(refreshToken, "12345678901")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_SHORT"));
        changePassword(refreshToken, "12345678901\u00a0x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_WHITESPACE_NOT_ALLOWED"));
        changePassword(refreshToken, "x".repeat(129))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));

        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_tokens token
                JOIN iam_refresh_token_families family ON family.id = token.family_id
                WHERE family.identity_id = ? AND token.consumed_at IS NOT NULL
                """, Integer.class, user.identity().id()));
    }

    @Test
    @Order(17)
    void outboxFailureRollsBackCredentialAndRestrictedSessionChanges() throws Exception {
        TestUser user = createUser(
                "password-rollback@example.test", "Initial-Rollback-2026!", false, Credential.ACTIVE_INITIAL);
        String refreshToken = refreshToken(login(
                "password-rollback@example.test", "Initial-Rollback-2026!", "PLATFORM").andReturn());

        assertThrows(IllegalArgumentException.class, () -> passwordChangeService.change(
                refreshToken, "Unique-Rollback-2026!", "invalid-trace-id"));

        Map<String, Object> facts = jdbc.queryForMap("""
                SELECT family.revoked_at, token.consumed_at,
                       (SELECT count(*) FROM iam_credentials credential
                        WHERE credential.identity_id = family.identity_id
                          AND credential.credential_type = 'PASSWORD') AS regular_count,
                       (SELECT count(*) FROM iam_credentials credential
                        WHERE credential.id = family.initial_credential_id
                          AND credential.invalidated_at IS NOT NULL) AS invalidated_initial_count
                FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertEquals(null, facts.get("revoked_at"));
        assertEquals(null, facts.get("consumed_at"));
        assertEquals(0L, facts.get("regular_count"));
        assertEquals(0L, facts.get("invalidated_initial_count"));
    }

    @Test
    @Order(18)
    void refreshRevalidatesEveryUserPurposeAndRotatesCurrentSessionFacts() throws Exception {
        TestUser platform = createUser("refresh-platform@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult platformLogin = login("refresh-platform@example.test", "correct-password", "PLATFORM").andReturn();
        String platformToken = refreshToken(platformLogin);
        String platformJti = tokenClaims(platformLogin).get("jti").asString();
        jdbc.update("""
                UPDATE iam_refresh_token_families
                SET absolute_expires_at = now() + interval '5 minutes'
                WHERE identity_id = ?
                """, platform.identity().id());

        MvcResult platformRefresh = refresh(platformToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=")))
                .andReturn();
        String rotatedPlatformToken = refreshToken(platformRefresh);
        String refreshedJti = tokenClaims(platformRefresh).get("jti").asString();
        assertFalse(platformToken.equals(rotatedPlatformToken));
        assertFalse(platformJti.equals(refreshedJti));
        assertEquals(7, UUID.fromString(refreshedJti).version());
        int maxAge = Integer.parseInt(Pattern.compile("Max-Age=(\\d+)")
                .matcher(platformRefresh.getResponse().getHeader("Set-Cookie")).results()
                .findFirst().orElseThrow().group(1));
        assertTrue(maxAge > 0 && maxAge <= 300);
        assertEquals(1, consumedRefreshTokenCount(platform.identity().id()));
        assertEquals(1, activeRefreshTokenCount(platform.identity().id()));
        assertEquals(2, accessTokenIssuanceCount(platform.identity().id()));

        TestUser tenant = createUser("refresh-tenant@example.test", "correct-password", false, Credential.REGULAR);
        UUID membershipId = uuidV7(40_001);
        UUID tenantId = uuidV7(40_002);
        accessibleMemberships(tenant.identity().id(), membership(membershipId, tenantId, "Tenant"));
        String tenantToken = refreshToken(
                login("refresh-tenant@example.test", "correct-password", "TENANT").andReturn());
        MvcResult tenantRefresh = refresh(tenantToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"))
                .andExpect(jsonPath("$.tenantContext.membershipId").value(membershipId.toString()))
                .andExpect(jsonPath("$.tenantContext.tenantId").value(tenantId.toString()))
                .andReturn();
        assertEquals(membershipId.toString(), tokenClaims(tenantRefresh).get("membershipId").asString());
        assertEquals(tenantId.toString(), tokenClaims(tenantRefresh).get("tenantId").asString());
        assertEquals(2, accessTokenIssuanceCount(tenant.identity().id()));

        TestUser selection = createUser(
                "refresh-selection@example.test", "correct-password", false, Credential.REGULAR);
        UUID firstMembership = uuidV7(41_001);
        UUID secondMembership = uuidV7(41_002);
        accessibleMemberships(selection.identity().id(),
                membership(firstMembership, uuidV7(41_003), "Alpha"),
                membership(secondMembership, uuidV7(41_004), "Beta"));
        String selectionToken = refreshToken(
                login("refresh-selection@example.test", "correct-password", "TENANT").andReturn());
        UUID replacementMembership = uuidV7(41_005);
        accessibleMemberships(selection.identity().id(),
                membership(secondMembership, uuidV7(41_004), "Beta"),
                membership(replacementMembership, uuidV7(41_006), "Gamma"));

        MvcResult selectionRefresh = refresh(selectionToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("CONTEXT_SELECTION_REQUIRED"))
                .andExpect(jsonPath("$.memberships[0].membershipId").value(secondMembership.toString()))
                .andExpect(jsonPath("$.memberships[1].membershipId").value(replacementMembership.toString()))
                .andReturn();
        assertFalse(selectionToken.equals(refreshToken(selectionRefresh)));
        assertEquals(1, consumedRefreshTokenCount(selection.identity().id()));
        assertEquals(1, activeRefreshTokenCount(selection.identity().id()));
        assertEquals(0, accessTokenIssuanceCount(selection.identity().id()));

        TestUser narrowedSelection = createUser(
                "refresh-selection-narrowed@example.test", "correct-password", false, Credential.REGULAR);
        UUID narrowedMembership = uuidV7(41_007);
        UUID narrowedTenant = uuidV7(41_008);
        accessibleMemberships(narrowedSelection.identity().id(),
                membership(narrowedMembership, narrowedTenant, "Alpha"),
                membership(uuidV7(41_009), uuidV7(41_010), "Beta"));
        String narrowedToken = refreshToken(
                login("refresh-selection-narrowed@example.test", "correct-password", "TENANT").andReturn());
        accessibleMemberships(narrowedSelection.identity().id(),
                membership(narrowedMembership, narrowedTenant, "Alpha"));
        MvcResult narrowedRefresh = refresh(narrowedToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"))
                .andExpect(jsonPath("$.tenantContext.membershipId").value(narrowedMembership.toString()))
                .andExpect(jsonPath("$.tenantContext.tenantId").value(narrowedTenant.toString()))
                .andReturn();
        assertEquals(narrowedMembership.toString(), tokenClaims(narrowedRefresh).get("membershipId").asString());
        assertEquals("USER_TENANT", jdbc.queryForObject(
                "SELECT family_purpose FROM iam_refresh_token_families WHERE identity_id = ?",
                String.class, narrowedSelection.identity().id()));
        assertEquals(1, accessTokenIssuanceCount(narrowedSelection.identity().id()));
    }

    @Test
    @Order(19)
    void refreshFailuresPreserveRetryableSessionsAndRevokeExplicitAuthorizationLoss() throws Exception {
        TestUser retryable = createUser(
                "refresh-retryable@example.test", "correct-password", false, Credential.REGULAR);
        UUID membershipId = uuidV7(42_001);
        UUID tenantId = uuidV7(42_002);
        accessibleMemberships(retryable.identity().id(), membership(membershipId, tenantId, "Tenant"));
        String retryableToken = refreshToken(
                login("refresh-retryable@example.test", "correct-password", "TENANT").andReturn());
        TENANT_ACCESS_FAILURES.add(retryable.identity().id());
        refresh(retryableToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertSessionUnchanged(retryable.identity().id(), 1);
        TENANT_ACCESS_FAILURES.remove(retryable.identity().id());
        refresh(retryableToken).andExpect(status().isOk());

        TestUser tenantLoss = createUser(
                "refresh-tenant-loss@example.test", "correct-password", false, Credential.REGULAR);
        accessibleMemberships(tenantLoss.identity().id(), membership(membershipId, tenantId, "Tenant"));
        String tenantLossToken = refreshToken(
                login("refresh-tenant-loss@example.test", "correct-password", "TENANT").andReturn());
        accessibleMemberships(tenantLoss.identity().id());
        refresh(tenantLossToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertEquals(1, consumedRefreshTokenCount(tenantLoss.identity().id()));
        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, tenantLoss.identity().id()));
        assertEquals(1, accessTokenIssuanceCount(tenantLoss.identity().id()));

        TestUser platformLoss = createUser(
                "refresh-platform-loss@example.test", "correct-password", true, Credential.REGULAR);
        String platformLossToken = refreshToken(
                login("refresh-platform-loss@example.test", "correct-password", "PLATFORM").andReturn());
        jdbc.update("UPDATE iam_platform_role_assignments SET revoked_at = now() WHERE identity_id = ?",
                platformLoss.identity().id());
        refresh(platformLossToken)
                .andExpect(status().isForbidden())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        TestUser selectionLoss = createUser(
                "refresh-selection-loss@example.test", "correct-password", false, Credential.REGULAR);
        accessibleMemberships(selectionLoss.identity().id(),
                membership(uuidV7(43_001), uuidV7(43_002), "Alpha"),
                membership(uuidV7(43_003), uuidV7(43_004), "Beta"));
        String selectionLossToken = refreshToken(
                login("refresh-selection-loss@example.test", "correct-password", "TENANT").andReturn());
        accessibleMemberships(selectionLoss.identity().id());
        refresh(selectionLossToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertEquals(1, consumedRefreshTokenCount(selectionLoss.identity().id()));
        assertEquals(0, accessTokenIssuanceCount(selectionLoss.identity().id()));

        TestUser initial = createUser(
                "refresh-initial@example.test", "Initial-Refresh-2026!", false, Credential.ACTIVE_INITIAL);
        String initialToken = refreshToken(
                login("refresh-initial@example.test", "Initial-Refresh-2026!", "PLATFORM").andReturn());
        refresh(initialToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_SESSION_INVALID"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertSessionUnchanged(initial.identity().id(), 0);

        TestUser signing = createUser(
                "refresh-signing@example.test", "correct-password", true, Credential.REGULAR);
        String signingToken = refreshToken(
                login("refresh-signing@example.test", "correct-password", "PLATFORM").andReturn());
        int outboxCount = jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events WHERE ordering_key = ?",
                Integer.class, signing.identity().id().toString());
        SIGNING_FAILURE.set(true);
        refresh(signingToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TOKEN_SIGNING_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertSessionUnchanged(signing.identity().id(), 1);
        assertEquals(outboxCount, jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events WHERE ordering_key = ?",
                Integer.class, signing.identity().id().toString()));
        SIGNING_FAILURE.set(false);
        refresh(signingToken).andExpect(status().isOk());
    }

    @Test
    @Order(20)
    void logoutWithoutCookieOrIdempotencyKeyIsStillSuccessfulAndClearsCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://platform.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionSlot\":\"PLATFORM\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("__Host-sf_platform_refresh="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"),
                        org.hamcrest.Matchers.containsString("Path=/"))));
    }

    @Test
    @Order(21)
    void logoutRevokesOnlyPresentedFamilyAndBearerJtiAndIsIdempotent() throws Exception {
        TestUser user = createUser("logout-isolation@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult currentSession = login("logout-isolation@example.test", "correct-password", "PLATFORM").andReturn();
        MvcResult otherSession = login("logout-isolation@example.test", "correct-password", "PLATFORM").andReturn();
        String currentRefreshToken = refreshToken(currentSession);
        String currentAccessToken = accessToken(currentSession);
        UUID currentJti = UUID.fromString(tokenClaims(currentSession).get("jti").asString());

        logout(currentRefreshToken, currentAccessToken)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        logout(currentRefreshToken, currentAccessToken).andExpect(status().isNoContent());

        byte[] currentDigest = MessageDigest.getInstance("SHA-256")
                .digest(currentRefreshToken.getBytes(StandardCharsets.UTF_8));
        assertNotNull(jdbc.queryForObject("""
                SELECT family.revoked_at FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE token.token_digest = ?
                """, Object.class, currentDigest));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_token_families
                WHERE identity_id = ? AND revoked_at IS NULL
                """, Integer.class, user.identity().id()));
        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, currentJti));

        String jtiDigest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(currentJti.toString().getBytes(StandardCharsets.UTF_8)));
        String revocationKey = "sf:test:iam-service:jwt-jti-revocation:v1:" + jtiDigest;
        assertEquals("1", redis.opsForValue().get(revocationKey));
        assertTrue(redis.getExpire(revocationKey) > 0);
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_outbox_events
                WHERE event_snapshot::jsonb ->> 'type' = 'com.saas.forge.iam.session.revoked.v1'
                  AND ordering_key = ?
                """, Integer.class, user.identity().id().toString()));

        refresh(refreshToken(otherSession)).andExpect(status().isOk());
    }

    @Test
    @Order(22)
    void postgresFailureAfterRedisWriteRollsBackFamilyAndOutboxButKeepsExtraRejection() throws Exception {
        TestUser user = createUser("logout-postgres-failure@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult session = login("logout-postgres-failure@example.test", "correct-password", "PLATFORM").andReturn();
        String refreshToken = refreshToken(session);
        String accessToken = accessToken(session);
        UUID jti = UUID.fromString(tokenClaims(session).get("jti").asString());

        setIamAppIssuanceUpdatePrivilege(false);
        try {
            logout(refreshToken, accessToken)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("LOGOUT_UNAVAILABLE"))
                    .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        } finally {
            setIamAppIssuanceUpdatePrivilege(true);
        }

        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, user.identity().id()));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, jti));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM iam_outbox_events
                WHERE event_snapshot::jsonb ->> 'type' = 'com.saas.forge.iam.session.revoked.v1'
                  AND ordering_key = ?
                """, Integer.class, user.identity().id().toString()));
        assertEquals("1", redis.opsForValue().get(jtiRevocationKey(jti)));
    }

    @Test
    @Order(23)
    void refreshRecoversSameKeyOnceThenTreatsSecondRetryAsFamilyReplay() throws Exception {
        TestUser user = createUser("refresh-recovery@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult login = login("refresh-recovery@example.test", "correct-password", "PLATFORM").andReturn();
        String oldRefreshToken = refreshToken(login);
        UUID originalJti = UUID.fromString(tokenClaims(login).get("jti").asString());
        UUID key = uuidV7(50_001);

        MvcResult first = refresh(oldRefreshToken, key)
                .andExpect(status().isOk())
                .andReturn();
        String firstSuccessor = refreshToken(first);
        UUID firstJti = UUID.fromString(tokenClaims(first).get("jti").asString());

        MvcResult recovered = refresh(oldRefreshToken, key)
                .andExpect(status().isOk())
                .andReturn();
        String replacement = refreshToken(recovered);
        UUID replacementJti = UUID.fromString(tokenClaims(recovered).get("jti").asString());
        assertFalse(firstSuccessor.equals(replacement));
        assertFalse(firstJti.equals(replacementJti));
        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, firstJti));
        assertEquals("1", redis.opsForValue().get(jtiRevocationKey(firstJti)));
        assertEquals(2, consumedRefreshTokenCount(user.identity().id()));
        assertEquals(1, activeRefreshTokenCount(user.identity().id()));

        refresh(oldRefreshToken, key)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_SESSION_INVALID"));

        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, user.identity().id()));
        for (UUID jti : List.of(originalJti, firstJti, replacementJti)) {
            assertNotNull(jdbc.queryForObject(
                    "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, jti));
            assertEquals("1", redis.opsForValue().get(jtiRevocationKey(jti)));
        }
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_outbox_events
                WHERE event_snapshot::jsonb ->> 'type' = 'com.saas.forge.iam.refresh-replay-detected.v1'
                  AND ordering_key = ?
                """, Integer.class, user.identity().id().toString()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_outbox_events
                WHERE event_snapshot::jsonb ->> 'type' = 'com.saas.forge.iam.session.revoked.v1'
                  AND event_snapshot::jsonb #>> '{data,result}' = 'CURRENT_SESSION_REVOKED'
                  AND ordering_key = ?
                """, Integer.class, user.identity().id().toString()));
    }

    @Test
    @Order(24)
    void differentKeyConflictsDuringLeaseAndBecomesReplayAfterLeaseEnds() throws Exception {
        TestUser user = createUser("refresh-lease@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult login = login("refresh-lease@example.test", "correct-password", "PLATFORM").andReturn();
        String oldRefreshToken = refreshToken(login);
        UUID firstKey = uuidV7(51_001);
        UUID secondKey = uuidV7(51_002);
        refresh(oldRefreshToken, firstKey).andExpect(status().isOk());
        int issuanceCount = accessTokenIssuanceCount(user.identity().id());

        refresh(oldRefreshToken, secondKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_ROTATION_IN_PROGRESS"))
                .andExpect(header().exists("Retry-After"));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, user.identity().id()));
        assertEquals(issuanceCount, accessTokenIssuanceCount(user.identity().id()));

        redis.delete(refreshRotationLeaseKey(oldRefreshToken));
        refresh(oldRefreshToken, secondKey)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_SESSION_INVALID"));
        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, user.identity().id()));
    }

    @Test
    @Order(25)
    void concurrentRefreshesRecoverSameKeyAndRejectDifferentKeyWithoutPartialState() throws Exception {
        TestUser sameKeyUser = createUser(
                "refresh-concurrent-same@example.test", "correct-password", true, Credential.REGULAR);
        String sameKeyToken = refreshToken(
                login("refresh-concurrent-same@example.test", "correct-password", "PLATFORM").andReturn());
        UUID sameKey = uuidV7(53_001);
        List<MvcResult> sameKeyResults = concurrentRefreshes(
                sameKeyToken, sameKey, sameKey);
        assertEquals(List.of(200, 200), sameKeyResults.stream()
                .map(result -> result.getResponse().getStatus()).sorted().toList());
        assertEquals(2, consumedRefreshTokenCount(sameKeyUser.identity().id()));
        assertEquals(1, activeRefreshTokenCount(sameKeyUser.identity().id()));
        assertEquals(3, accessTokenIssuanceCount(sameKeyUser.identity().id()));

        TestUser differentKeyUser = createUser(
                "refresh-concurrent-different@example.test", "correct-password", true, Credential.REGULAR);
        String differentKeyToken = refreshToken(
                login("refresh-concurrent-different@example.test", "correct-password", "PLATFORM").andReturn());
        List<MvcResult> differentKeyResults = concurrentRefreshes(
                differentKeyToken, uuidV7(53_002), uuidV7(53_003));
        assertEquals(List.of(200, 409), differentKeyResults.stream()
                .map(result -> result.getResponse().getStatus()).sorted().toList());
        MvcResult conflict = differentKeyResults.stream()
                .filter(result -> result.getResponse().getStatus() == 409)
                .findFirst().orElseThrow();
        assertEquals("REFRESH_ROTATION_IN_PROGRESS",
                json(conflict.getResponse().getContentAsByteArray()).get("code").asString());
        assertNotNull(conflict.getResponse().getHeader("Retry-After"));
        assertEquals(1, consumedRefreshTokenCount(differentKeyUser.identity().id()));
        assertEquals(1, activeRefreshTokenCount(differentKeyUser.identity().id()));
        assertEquals(2, accessTokenIssuanceCount(differentKeyUser.identity().id()));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, differentKeyUser.identity().id()));
    }

    @Test
    @Order(0)
    void internalServiceTokenSigningCommitsBeforeOuterTransactionRollback() {
        SERVICE_SIGNING_TRANSACTION.set(null);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(
                webApplicationContext.getBean(PlatformTransactionManager.class));
        String token = transactions.execute(status -> {
            String issued = SERVICE_TOKENS.get().membershipReadToken();
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            status.setRollbackOnly();
            return issued;
        });
        assertNotNull(token);
        assertEquals(Boolean.FALSE, SERVICE_SIGNING_TRANSACTION.get());
        assertTrue(jdbc.queryForObject("SELECT max_issued_token_ttl_seconds FROM iam_signing_keys "
                + "WHERE key_status = 'ACTIVE'", Long.class) >= 300L);
    }

    @Test
    @Order(26)
    void clientCredentialsEndpointRejectsWrongSecretRevokedClientAndOverprivilegedScope() throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        UUID clientId = uuidV7(53_100);
        String secret = serviceClientSecret((byte) 11);
        oauthClients.createWithId(
                OAuthClient.register("iam-service", Set.of(
                                OAuthScope.RUNTIME_READ,
                                OAuthScope.RUNTIME_QUOTA_WRITE), now)
                        .identifiedBy(clientId),
                ClientSecretDigest.fromPlaintext(secret), now);

        MvcResult issued = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
                        .param("grant_type", "client_credentials")
                        .param("scope", "runtime:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(300))
                .andExpect(jsonPath("$.scope").value("runtime:read"))
                .andReturn();
        String token = json(issued.getResponse().getContentAsByteArray()).get("access_token").asString();
        JsonNode header = json(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
        JsonNode claims = json(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertEquals("at+jwt", header.get("typ").asString());
        assertEquals(clientId.toString(), claims.get("client_id").asString());
        assertEquals(Set.of("iss", "aud", "iat", "exp", "jti", "sub", "client_id", "scope"),
                claims.propertyNames());

        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
                        .param("grant_type", "client_credentials")
                        .param("scope", "runtime:quota:write"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("runtime:quota:write"));

        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(clientId, serviceClientSecret((byte) 12)))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
                        .param("grant_type", "client_credentials")
                        .param("scope", "iam:identity:write"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CLIENT_CREDENTIALS_SCOPE_REJECTED"));

        oauthClients.revoke(clientId, Instant.now());
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(27)
    void tenantSwitchNoOpUsesOnlyCookieAndReplaysWithoutSideEffects() throws Exception {
        TestUser user = createUser("tenant-switch-noop@example.test", "correct-password", false, Credential.REGULAR);
        UUID membershipId = uuidV7(54_001);
        UUID tenantId = uuidV7(54_002);
        UUID key = uuidV7(54_003);
        accessibleMemberships(user.identity().id(), membership(membershipId, tenantId, "Current Tenant"));
        MvcResult session = login("tenant-switch-noop@example.test", "correct-password", "TENANT").andReturn();

        mockMvc.perform(tenantSwitch(refreshToken(session), key, membershipId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ignored-by-cookie-only-switch"))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mockMvc.perform(tenantSwitch(refreshToken(session), key, membershipId))
                .andExpect(status().isNoContent());

        Map<String, Object> workflow = jdbc.queryForMap("""
                SELECT switch_status, completed_at, count(*) OVER () AS workflow_count
                FROM iam_tenant_context_switches
                WHERE idempotency_key = ?
                """, key);
        assertEquals("NO_OP", workflow.get("switch_status"));
        assertNotNull(workflow.get("completed_at"));
        assertEquals(1L, ((Number) workflow.get("workflow_count")).longValue());
        Map<String, Object> sessionState = jdbc.queryForMap("""
                SELECT family.membership_id, family.tenant_id, family.revoked_at, issuance.revoked_at AS token_revoked_at
                FROM iam_refresh_token_families family
                JOIN iam_access_token_issuances issuance ON issuance.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertEquals(membershipId, sessionState.get("membership_id"));
        assertEquals(tenantId, sessionState.get("tenant_id"));
        assertEquals(null, sessionState.get("revoked_at"));
        assertEquals(null, sessionState.get("token_revoked_at"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events "
                        + "WHERE event_snapshot ->> 'type' = 'com.saas.forge.iam.tenant-context-switched.v1'",
                Integer.class));

        mockMvc.perform(post("/api/v1/auth/tenant-switches")
                        .header("Idempotency-Key", uuidV7(54_004).toString())
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://evil.test")
                        .cookie(new Cookie("__Host-sf_tenant_refresh", refreshToken(session)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of("membershipId", membershipId))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BROWSER_REQUEST_REJECTED"));
        mockMvc.perform(post("/api/v1/auth/tenant-switches")
                        .header("Idempotency-Key", uuidV7(54_005).toString())
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://console.saas.forge.test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of("membershipId", membershipId))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_SWITCH_SESSION_INVALID"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    @Order(28)
    void tenantSwitchRevokesTheWholeFamilyAndRefreshesIntoTheTargetContext() throws Exception {
        TestUser user = createUser("tenant-switch-complete@example.test", "correct-password", false, Credential.REGULAR);
        UUID currentMembershipId = uuidV7(55_001);
        UUID currentTenantId = uuidV7(55_002);
        UUID targetMembershipId = uuidV7(55_003);
        UUID targetTenantId = uuidV7(55_004);
        UUID key = uuidV7(55_006);
        accessibleMemberships(user.identity().id(), membership(currentMembershipId, currentTenantId, "Current"));
        MvcResult session = login("tenant-switch-complete@example.test", "correct-password", "TENANT").andReturn();
        UUID firstJti = UUID.fromString(tokenClaims(session).get("jti").asString());
        MvcResult rotated = refresh(refreshToken(session), uuidV7(55_005))
                .andExpect(status().isOk())
                .andReturn();
        UUID secondJti = UUID.fromString(tokenClaims(rotated).get("jti").asString());

        MvcResult otherFamily = login(
                "tenant-switch-complete@example.test", "correct-password", "TENANT").andReturn();
        UUID otherFamilyJti = UUID.fromString(tokenClaims(otherFamily).get("jti").asString());
        accessibleMemberships(user.identity().id(),
                membership(currentMembershipId, currentTenantId, "Current"),
                membership(targetMembershipId, targetTenantId, "Target"));

        mockMvc.perform(tenantSwitch(refreshToken(rotated), key, targetMembershipId))
                .andExpect(status().isNoContent());
        mockMvc.perform(tenantSwitch(refreshToken(rotated), key, targetMembershipId))
                .andExpect(status().isNoContent());
        mockMvc.perform(tenantSwitch(refreshToken(rotated), uuidV7(55_007), targetMembershipId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_SWITCH_REFRESH_REQUIRED"));

        Map<String, Object> workflow = jdbc.queryForMap("""
                SELECT switch_status, result_http_status, completed_at, refreshed_at
                FROM iam_tenant_context_switches WHERE idempotency_key = ?
                """, key);
        assertEquals("AWAITING_REFRESH", workflow.get("switch_status"));
        assertEquals(204, ((Number) workflow.get("result_http_status")).intValue());
        assertNotNull(workflow.get("completed_at"));
        assertEquals(null, workflow.get("refreshed_at"));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM iam_access_token_issuances
                WHERE jti IN (?, ?) AND revoked_at IS NOT NULL
                """, Integer.class, firstJti, secondJti));
        assertTrue(revocationIndex.isJtiRevoked(firstJti));
        assertTrue(revocationIndex.isJtiRevoked(secondJti));
        assertTrue(redis.getExpire(jtiRevocationKey(firstJti)) > 0);
        assertTrue(redis.getExpire(jtiRevocationKey(secondJti)) > 0);
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?",
                Object.class, otherFamilyJti));
        Map<String, Object> otherContext = jdbc.queryForMap("""
                SELECT family.membership_id, family.tenant_id
                FROM iam_refresh_token_families family
                JOIN iam_access_token_issuances issuance ON issuance.family_id = family.id
                WHERE issuance.jti = ?
                """, otherFamilyJti);
        assertEquals(currentMembershipId, otherContext.get("membership_id"));
        assertEquals(currentTenantId, otherContext.get("tenant_id"));

        String eventSnapshot = jdbc.queryForObject(
                "SELECT event_snapshot::text FROM iam_outbox_events "
                        + "WHERE event_snapshot ->> 'type' = 'com.saas.forge.iam.tenant-context-switched.v1'",
                String.class);
        JsonNode event = new ObjectMapper().readTree(eventSnapshot);
        assertEquals(Set.of("identityId", "previousMembershipId", "membershipId", "tenantId"),
                event.get("data").propertyNames());
        assertEquals(user.identity().id().toString(), event.get("data").get("identityId").asString());
        assertEquals(currentMembershipId.toString(), event.get("data").get("previousMembershipId").asString());
        assertEquals(targetMembershipId.toString(), event.get("data").get("membershipId").asString());
        assertEquals(targetTenantId.toString(), event.get("data").get("tenantId").asString());

        MvcResult postSwitchRefresh = refresh(refreshToken(rotated), uuidV7(55_008))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantContext.membershipId").value(targetMembershipId.toString()))
                .andExpect(jsonPath("$.tenantContext.tenantId").value(targetTenantId.toString()))
                .andExpect(jsonPath("$.tenantContext.accessibleMemberships.length()").value(2))
                .andReturn();
        assertEquals(targetMembershipId.toString(), tokenClaims(postSwitchRefresh).get("membershipId").asString());
        assertEquals(targetTenantId.toString(), tokenClaims(postSwitchRefresh).get("tenantId").asString());
        assertEquals("POST_SWITCH_REFRESHED", jdbc.queryForObject(
                "SELECT switch_status FROM iam_tenant_context_switches WHERE idempotency_key = ?",
                String.class, key));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events "
                        + "WHERE event_snapshot ->> 'type' = 'com.saas.forge.iam.tenant-context-switched.v1'",
                Integer.class));
        mockMvc.perform(tenantSwitch(refreshToken(postSwitchRefresh), key, targetMembershipId))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(29)
    void tenantSwitchDatabaseFailureRollsBackContextRevocationsAndEventButKeepsRedisRejection() throws Exception {
        TestUser user = createUser(
                "tenant-switch-postgres-failure@example.test", "correct-password", false, Credential.REGULAR);
        UUID currentMembership = uuidV7(55_101);
        UUID currentTenant = uuidV7(55_102);
        UUID targetMembership = uuidV7(55_103);
        UUID targetTenant = uuidV7(55_104);
        UUID key = uuidV7(55_105);
        accessibleMemberships(user.identity().id(), membership(currentMembership, currentTenant, "Current"));
        MvcResult session = login(
                "tenant-switch-postgres-failure@example.test", "correct-password", "TENANT").andReturn();
        UUID jti = UUID.fromString(tokenClaims(session).get("jti").asString());
        UUID familyId = jdbc.queryForObject(
                "SELECT family_id FROM iam_access_token_issuances WHERE jti = ?", UUID.class, jti);
        accessibleMemberships(user.identity().id(),
                membership(currentMembership, currentTenant, "Current"),
                membership(targetMembership, targetTenant, "Target"));

        setIamAppIssuanceUpdatePrivilege(false);
        try {
            mockMvc.perform(tenantSwitch(refreshToken(session), key, targetMembership))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_SWITCH_PENDING"))
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
        } finally {
            setIamAppIssuanceUpdatePrivilege(true);
        }

        Map<String, Object> family = jdbc.queryForMap("""
                SELECT membership_id, tenant_id, context_version
                FROM iam_refresh_token_families WHERE identity_id = ?
                """, user.identity().id());
        assertEquals(currentMembership, family.get("membership_id"));
        assertEquals(currentTenant, family.get("tenant_id"));
        assertEquals(0L, ((Number) family.get("context_version")).longValue());
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, jti));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT switch_status FROM iam_tenant_context_switches WHERE idempotency_key = ?",
                String.class, key));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events WHERE event_snapshot ->> 'type' = "
                        + "'com.saas.forge.iam.tenant-context-switched.v1' AND event_snapshot ->> 'subject' = ?",
                Integer.class, familyId.toString()));
        assertEquals("1", redis.opsForValue().get(jtiRevocationKey(jti)));
    }

    @Test
    @Order(30)
    void tenantSwitchMapsMembershipDenialOutageAndWrongPurposeWithoutPartialMutation() throws Exception {
        TestUser currentDenied = createUser(
                "tenant-switch-current-denied@example.test", "correct-password", false, Credential.REGULAR);
        UUID currentMembership = uuidV7(56_001);
        UUID currentTenant = uuidV7(56_002);
        accessibleMemberships(currentDenied.identity().id(), membership(currentMembership, currentTenant, "Current"));
        MvcResult currentSession = login(
                "tenant-switch-current-denied@example.test", "correct-password", "TENANT").andReturn();
        accessibleMemberships(currentDenied.identity().id());
        mockMvc.perform(tenantSwitch(refreshToken(currentSession), uuidV7(56_003), uuidV7(56_004)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, currentDenied.identity().id()));

        TestUser targetDenied = createUser(
                "tenant-switch-target-denied@example.test", "correct-password", false, Credential.REGULAR);
        UUID targetCurrentMembership = uuidV7(56_005);
        UUID targetCurrentTenant = uuidV7(56_006);
        accessibleMemberships(targetDenied.identity().id(),
                membership(targetCurrentMembership, targetCurrentTenant, "Current"));
        MvcResult targetSession = login(
                "tenant-switch-target-denied@example.test", "correct-password", "TENANT").andReturn();
        mockMvc.perform(tenantSwitch(refreshToken(targetSession), uuidV7(56_007), uuidV7(56_008)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, targetDenied.identity().id()));

        TestUser outage = createUser(
                "tenant-switch-outage@example.test", "correct-password", false, Credential.REGULAR);
        UUID outageMembership = uuidV7(56_009);
        UUID outageTenant = uuidV7(56_010);
        accessibleMemberships(outage.identity().id(), membership(outageMembership, outageTenant, "Current"));
        MvcResult outageSession = login("tenant-switch-outage@example.test", "correct-password", "TENANT").andReturn();
        TENANT_ACCESS_FAILURES.add(outage.identity().id());
        mockMvc.perform(tenantSwitch(refreshToken(outageSession), uuidV7(56_011), uuidV7(56_012)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_SWITCH_PENDING"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, outage.identity().id()));

        TestUser platform = createUser(
                "tenant-switch-platform@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult platformSession = login(
                "tenant-switch-platform@example.test", "correct-password", "PLATFORM").andReturn();
        mockMvc.perform(tenantSwitch(refreshToken(platformSession), uuidV7(56_013), uuidV7(56_014)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_SWITCH_SESSION_INVALID"));
    }

    @Test
    @Order(29)
    void tenantSwitchWorkerTakesOverExpiredLeaseAfterRestartWithoutDuplicateFacts() throws Exception {
        TestUser user = createUser(
                "tenant-switch-restart@example.test", "correct-password", false, Credential.REGULAR);
        UUID currentMembership = uuidV7(87_001);
        UUID currentTenant = uuidV7(87_002);
        UUID targetMembership = uuidV7(87_003);
        UUID targetTenant = uuidV7(87_004);
        UUID key = uuidV7(87_005);
        accessibleMemberships(user.identity().id(), membership(currentMembership, currentTenant, "Current"));
        MvcResult session = login(
                "tenant-switch-restart@example.test", "correct-password", "TENANT").andReturn();
        accessibleMemberships(user.identity().id(),
                membership(currentMembership, currentTenant, "Current"),
                membership(targetMembership, targetTenant, "Target"));
        TENANT_ACCESS_FAILURES.add(user.identity().id());

        mockMvc.perform(tenantSwitch(refreshToken(session), key, targetMembership))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_SWITCH_PENDING"));
        mockMvc.perform(tenantSwitch(refreshToken(session), key, targetMembership))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_SWITCH_PENDING"));
        mockMvc.perform(tenantSwitch(refreshToken(session), uuidV7(87_006), targetMembership))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_SWITCH_IN_PROGRESS"));

        Map<String, Object> pending = jdbc.queryForMap("""
                SELECT id, target_fingerprint, attempt_count, next_attempt_at, last_failure
                FROM iam_tenant_context_switches
                WHERE idempotency_key = ? AND family_id = (
                    SELECT id FROM iam_refresh_token_families WHERE identity_id = ?)
                """, key, user.identity().id());
        assertEquals(1, ((Number) pending.get("attempt_count")).intValue());
        assertEquals("TENANT_ACCESS_UNAVAILABLE", pending.get("last_failure"));
        byte[] targetFingerprint = (byte[]) pending.get("target_fingerprint");

        jdbc.update("""
                UPDATE iam_tenant_context_switches
                SET next_attempt_at = now() - interval '1 second',
                    lease_owner = 'dead-process', lease_until = now() - interval '1 second'
                WHERE id = ?
                """, pending.get("id"));
        jdbc.update("""
                UPDATE iam_tenant_context_switches
                SET next_attempt_at = now() + interval '1 day'
                WHERE switch_status = 'PENDING' AND id <> ?
                """, pending.get("id"));
        TENANT_ACCESS_FAILURES.remove(user.identity().id());

        tenantContextSwitchService.recoverNext();
        tenantContextSwitchService.recoverNext();

        Map<String, Object> recovered = jdbc.queryForMap("""
                SELECT switch_status, target_fingerprint, attempt_count, lease_owner
                FROM iam_tenant_context_switches WHERE id = ?
                """, pending.get("id"));
        assertEquals("AWAITING_REFRESH", recovered.get("switch_status"));
        assertArrayEquals(targetFingerprint, (byte[]) recovered.get("target_fingerprint"));
        assertEquals(2, ((Number) recovered.get("attempt_count")).intValue());
        assertEquals(null, recovered.get("lease_owner"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events WHERE event_snapshot ->> 'type' = "
                        + "'com.saas.forge.iam.tenant-context-switched.v1' "
                        + "AND event_snapshot ->> 'subject' = (SELECT family_id::text "
                        + "FROM iam_tenant_context_switches WHERE id = ?)",
                Integer.class, pending.get("id")));
    }

    @Test
    @Order(30)
    void exhaustedSwitchStopsWorkerAndAllowsNewKeyFromAuthoritativeFamilyState() throws Exception {
        TestUser user = createUser(
                "tenant-switch-exhausted@example.test", "correct-password", false, Credential.REGULAR);
        UUID currentMembership = uuidV7(88_001);
        UUID currentTenant = uuidV7(88_002);
        UUID targetMembership = uuidV7(88_003);
        UUID targetTenant = uuidV7(88_004);
        UUID exhaustedKey = uuidV7(88_005);
        UUID newKey = uuidV7(88_006);
        accessibleMemberships(user.identity().id(), membership(currentMembership, currentTenant, "Current"));
        MvcResult session = login(
                "tenant-switch-exhausted@example.test", "correct-password", "TENANT").andReturn();
        accessibleMemberships(user.identity().id(),
                membership(currentMembership, currentTenant, "Current"),
                membership(targetMembership, targetTenant, "Target"));
        TENANT_ACCESS_FAILURES.add(user.identity().id());

        mockMvc.perform(tenantSwitch(refreshToken(session), exhaustedKey, targetMembership))
                .andExpect(status().isServiceUnavailable());
        jdbc.update("""
                UPDATE iam_tenant_context_switches
                SET attempt_count = 10, next_attempt_at = now() - interval '1 second',
                    lease_owner = 'crashed-final-worker', lease_until = now() - interval '1 second'
                WHERE idempotency_key = ?
                """, exhaustedKey);
        jdbc.update("""
                UPDATE iam_tenant_context_switches
                SET next_attempt_at = now() + interval '1 day'
                WHERE switch_status = 'PENDING' AND idempotency_key <> ?
                """, exhaustedKey);

        tenantContextSwitchService.recoverNext();
        tenantContextSwitchService.recoverNext();

        Map<String, Object> exhausted = jdbc.queryForMap("""
                SELECT attempt_count, recovery_exhausted_at, last_failure, lease_owner
                FROM iam_tenant_context_switches WHERE idempotency_key = ?
                """, exhaustedKey);
        assertEquals(10, ((Number) exhausted.get("attempt_count")).intValue());
        assertNotNull(exhausted.get("recovery_exhausted_at"));
        assertEquals("RECOVERY_ATTEMPT_LIMIT_REACHED", exhausted.get("last_failure"));
        assertEquals(null, exhausted.get("lease_owner"));
        mockMvc.perform(tenantSwitch(refreshToken(session), exhaustedKey, targetMembership))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_SWITCH_RETRY_REQUIRED"));

        TENANT_ACCESS_FAILURES.remove(user.identity().id());
        mockMvc.perform(tenantSwitch(refreshToken(session), newKey, targetMembership))
                .andExpect(status().isNoContent());
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM iam_tenant_context_switches WHERE family_id = "
                        + "(SELECT family_id FROM iam_tenant_context_switches WHERE idempotency_key = ?)",
                Integer.class, exhaustedKey));
        assertEquals("AWAITING_REFRESH", jdbc.queryForObject(
                "SELECT switch_status FROM iam_tenant_context_switches WHERE idempotency_key = ?",
                String.class, newKey));
    }

    @Test
    @Order(31)
    void leaseAttemptFencesLateWorkerBeforeAndAfterTakeoverCommit() throws Exception {
        TestUser user = createUser(
                "tenant-switch-fencing@example.test", "correct-password", false, Credential.REGULAR);
        UUID currentMembership = uuidV7(89_001);
        UUID currentTenant = uuidV7(89_002);
        UUID targetMembership = uuidV7(89_003);
        UUID targetTenant = uuidV7(89_004);
        UUID key = uuidV7(89_005);
        accessibleMemberships(user.identity().id(), membership(currentMembership, currentTenant, "Current"));
        MvcResult session = login(
                "tenant-switch-fencing@example.test", "correct-password", "TENANT").andReturn();
        UUID familyId = jdbc.queryForObject(
                "SELECT id FROM iam_refresh_token_families WHERE identity_id = ?", UUID.class, user.identity().id());
        accessibleMemberships(user.identity().id(),
                membership(currentMembership, currentTenant, "Current"),
                membership(targetMembership, targetTenant, "Target"));
        TENANT_ACCESS_FAILURES.add(user.identity().id());

        mockMvc.perform(tenantSwitch(refreshToken(session), key, targetMembership))
                .andExpect(status().isServiceUnavailable());
        UUID workflowId = jdbc.queryForObject(
                "SELECT id FROM iam_tenant_context_switches WHERE family_id = ? AND idempotency_key = ?",
                UUID.class, familyId, key);
        TenantContextSwitchWorkflow stale = tenantContextSwitches.findById(workflowId).orElseThrow();
        jdbc.update("""
                UPDATE iam_tenant_context_switches
                SET next_attempt_at = now() - interval '1 second',
                    lease_owner = 'dead-worker', lease_until = now() - interval '1 second'
                WHERE id = ?
                """, workflowId);
        jdbc.update("""
                UPDATE iam_tenant_context_switches
                SET next_attempt_at = now() + interval '1 day'
                WHERE switch_status = 'PENDING' AND id <> ?
                """, workflowId);
        Instant claimedAt = Instant.now();
        TenantContextSwitchWorkflow takeover = tenantContextSwitches.claimNext(
                "takeover-worker", claimedAt, claimedAt.plusSeconds(30), 10).orElseThrow();
        RefreshTokenFamily originalFamily = refreshTokenFamilies.findById(familyId).orElseThrow();

        assertThrows(IllegalStateException.class, () -> tenantContextSwitchTransaction.switchContext(
                stale, originalFamily, 0, targetMembership, targetTenant, Instant.now(), null));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT context_version FROM iam_refresh_token_families WHERE id = ?", Long.class, familyId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events WHERE event_snapshot ->> 'subject' = ? "
                        + "AND event_snapshot ->> 'type' = 'com.saas.forge.iam.tenant-context-switched.v1'",
                Integer.class, familyId.toString()));

        tenantContextSwitchTransaction.switchContext(
                takeover, originalFamily, 0, targetMembership, targetTenant, Instant.now(), null);
        assertThrows(IllegalStateException.class, () -> tenantContextSwitchTransaction.switchContext(
                stale, originalFamily, 0, targetMembership, targetTenant, Instant.now(), null));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT context_version FROM iam_refresh_token_families WHERE id = ?", Long.class, familyId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events WHERE event_snapshot ->> 'subject' = ? "
                        + "AND event_snapshot ->> 'type' = 'com.saas.forge.iam.tenant-context-switched.v1'",
                Integer.class, familyId.toString()));
    }

    @Test
    @Order(32)
    void recoveryFailsClosedUntilDurableJtiAndKidRevocationsAreRebuilt() throws Exception {
        TestUser user = createUser("revocation-recovery@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult session = login("revocation-recovery@example.test", "correct-password", "PLATFORM").andReturn();
        UUID jti = UUID.fromString(tokenClaims(session).get("jti").asString());
        UUID activeKeyId = jdbc.queryForObject(
                "SELECT id FROM iam_signing_keys WHERE kid = 'active-login-kid'", UUID.class);
        UUID replacementKeyId = jdbc.queryForObject("""
                INSERT INTO iam_signing_keys
                    (kid, key_version_reference, public_jwk_modulus, public_jwk_exponent,
                     key_status, published_at)
                VALUES ('emergency-replacement-kid', 'fake/key/emergency', 'replacement-modulus', 'AQAB',
                        'PUBLISHED', now() - interval '10 minutes')
                RETURNING id
                """, UUID.class);
        try {
            signingKeyLifecycleService.emergencyRevoke(activeKeyId, replacementKeyId);
            assertEquals("REVOKED", jdbc.queryForObject(
                    "SELECT key_status FROM iam_signing_keys WHERE id = ?", String.class, activeKeyId));
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT key_status FROM iam_signing_keys WHERE id = ?", String.class, replacementKeyId));
            assertEquals("SIGNING_KEY_COMPROMISED", jdbc.queryForObject(
                    "SELECT revocation_reason FROM iam_access_token_issuances WHERE jti = ?", String.class, jti));
            Set<String> revocationKeys = redis.keys("sf:test:iam-service:*revocation*:v1:*");
            if (revocationKeys != null && !revocationKeys.isEmpty()) {
                redis.delete(revocationKeys);
            }
            revocationIndex.markNotReady();
            assertThrows(RevocationIndexUnavailableException.class,
                    () -> revocationIndex.isTokenRevoked(jti, "active-login-kid"));

            revocationIndexRecovery.recover();

            assertTrue(revocationIndex.isJtiRevoked(jti));
            assertTrue(revocationIndex.isKidRevoked("active-login-kid"));
            assertTrue(revocationIndex.isTokenRevoked(jti, "active-login-kid"));
            String kidDigest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest("active-login-kid".getBytes(StandardCharsets.UTF_8)));
            assertEquals("1", redis.opsForValue().get(
                    "sf:test:iam-service:signing-kid-revocation:v1:" + kidDigest));
            assertEquals("1", redis.opsForValue().get(
                    "sf:test:iam-service:revocation-index-ready:v1:state"));
        } finally {
            jdbc.update("""
                    UPDATE iam_signing_keys
                    SET key_status = 'PUBLISHED', activated_at = NULL
                    WHERE id = ?
                    """, replacementKeyId);
            jdbc.update("""
                    UPDATE iam_signing_keys
                    SET key_status = 'ACTIVE', revoked_at = NULL
                    WHERE kid = 'active-login-kid'
                    """);
            // 恢复共享测试密钥时同步清理其撤销标记，保留已撤销 Token 的 JTI 记录。
            String restoredKidDigest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest("active-login-kid".getBytes(StandardCharsets.UTF_8)));
            redis.delete("sf:test:iam-service:signing-kid-revocation:v1:" + restoredKidDigest);
            try (Connection connection = DriverManager.getConnection(
                    iamJdbcUrl(), "iam_migrator", "iam-migrator-password")) {
                try (var statement = connection.prepareStatement(
                        "DELETE FROM iam_signing_keys WHERE id = ?")) {
                    statement.setObject(1, replacementKeyId);
                    statement.executeUpdate();
                }
            }
        }
    }

    @Test
    @Order(33)
    void postSwitchRefreshAuthorizationLossRevokesTheFamilyAndClearsTheCookie() throws Exception {
        TestUser user = createUser(
                "post-switch-refresh-denied@example.test", "correct-password", false, Credential.REGULAR);
        UUID currentMembership = uuidV7(57_001);
        UUID currentTenant = uuidV7(57_002);
        UUID targetMembership = uuidV7(57_003);
        UUID targetTenant = uuidV7(57_004);
        UUID key = uuidV7(57_005);
        accessibleMemberships(user.identity().id(), membership(currentMembership, currentTenant, "Current"));
        MvcResult session = login(
                "post-switch-refresh-denied@example.test", "correct-password", "TENANT").andReturn();
        UUID originalJti = UUID.fromString(tokenClaims(session).get("jti").asString());
        accessibleMemberships(user.identity().id(),
                membership(currentMembership, currentTenant, "Current"),
                membership(targetMembership, targetTenant, "Target"));

        mockMvc.perform(tenantSwitch(refreshToken(session), key, targetMembership))
                .andExpect(status().isNoContent());
        accessibleMemberships(user.identity().id(), membership(currentMembership, currentTenant, "Current"));

        refresh(refreshToken(session), uuidV7(57_006))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")));

        Map<String, Object> family = jdbc.queryForMap("""
                SELECT family.revoked_at, count(issuance.jti) AS issuance_count,
                       count(issuance.revoked_at) AS revoked_issuance_count
                FROM iam_refresh_token_families family
                JOIN iam_access_token_issuances issuance ON issuance.family_id = family.id
                WHERE issuance.jti = ?
                GROUP BY family.id
                """, originalJti);
        assertNotNull(family.get("revoked_at"));
        assertEquals(1L, ((Number) family.get("issuance_count")).longValue());
        assertEquals(1L, ((Number) family.get("revoked_issuance_count")).longValue());
        assertEquals("POST_SWITCH_REFRESH_REJECTED", jdbc.queryForObject(
                "SELECT switch_status FROM iam_tenant_context_switches WHERE idempotency_key = ?",
                String.class, key));
    }

    @Test
    @Order(34)
    void switchAndRefreshSerializeInBothOrdersWithoutPersistingStaleContextTokens() throws Exception {
        TestUser refreshFirst = createUser(
                "tenant-switch-refresh-first@example.test", "correct-password", false, Credential.REGULAR);
        UUID refreshFirstCurrentMembership = uuidV7(91_001);
        UUID refreshFirstCurrentTenant = uuidV7(91_002);
        UUID refreshFirstTargetMembership = uuidV7(91_003);
        UUID refreshFirstTargetTenant = uuidV7(91_004);
        accessibleMemberships(refreshFirst.identity().id(),
                membership(refreshFirstCurrentMembership, refreshFirstCurrentTenant, "Current"));
        MvcResult refreshFirstSession = login(
                "tenant-switch-refresh-first@example.test", "correct-password", "TENANT").andReturn();
        accessibleMemberships(refreshFirst.identity().id(),
                membership(refreshFirstCurrentMembership, refreshFirstCurrentTenant, "Current"),
                membership(refreshFirstTargetMembership, refreshFirstTargetTenant, "Target"));

        ConcurrencyGate switchValidation = new ConcurrencyGate();
        MEMBERSHIP_VALIDATION_GATE.set(switchValidation);
        var refreshFirstExecutor = Executors.newSingleThreadExecutor();
        try {
            var switchResult = refreshFirstExecutor.submit(() -> mockMvc.perform(tenantSwitch(
                    refreshToken(refreshFirstSession), uuidV7(91_005), refreshFirstTargetMembership)).andReturn());
            switchValidation.awaitEntered();
            MvcResult refreshCommittedFirst = refresh(
                    refreshToken(refreshFirstSession), uuidV7(91_006))
                    .andExpect(status().isOk())
                    .andReturn();
            assertEquals(refreshFirstCurrentMembership.toString(),
                    tokenClaims(refreshCommittedFirst).get("membershipId").asString());
            switchValidation.release();
            assertEquals(204, switchResult.get().getResponse().getStatus());

            MvcResult targetRefresh = refresh(refreshToken(refreshCommittedFirst), uuidV7(91_007))
                    .andExpect(status().isOk())
                    .andReturn();
            assertEquals(refreshFirstTargetMembership.toString(),
                    tokenClaims(targetRefresh).get("membershipId").asString());
            assertEquals(refreshFirstTargetTenant.toString(), tokenClaims(targetRefresh).get("tenantId").asString());
        } finally {
            switchValidation.release();
            refreshFirstExecutor.shutdownNow();
        }

        TestUser switchFirst = createUser(
                "tenant-switch-switch-first@example.test", "correct-password", false, Credential.REGULAR);
        UUID switchFirstCurrentMembership = uuidV7(92_001);
        UUID switchFirstCurrentTenant = uuidV7(92_002);
        UUID switchFirstTargetMembership = uuidV7(92_003);
        UUID switchFirstTargetTenant = uuidV7(92_004);
        accessibleMemberships(switchFirst.identity().id(),
                membership(switchFirstCurrentMembership, switchFirstCurrentTenant, "Current"));
        MvcResult switchFirstSession = login(
                "tenant-switch-switch-first@example.test", "correct-password", "TENANT").andReturn();
        accessibleMemberships(switchFirst.identity().id(),
                membership(switchFirstCurrentMembership, switchFirstCurrentTenant, "Current"),
                membership(switchFirstTargetMembership, switchFirstTargetTenant, "Target"));

        ConcurrencyGate refreshSigning = new ConcurrencyGate();
        SIGNING_GATE.set(refreshSigning);
        var switchFirstExecutor = Executors.newSingleThreadExecutor();
        try {
            var staleRefresh = switchFirstExecutor.submit(() -> refresh(
                    refreshToken(switchFirstSession), uuidV7(92_005)).andReturn());
            refreshSigning.awaitEntered();
            mockMvc.perform(tenantSwitch(
                            refreshToken(switchFirstSession), uuidV7(92_006), switchFirstTargetMembership))
                    .andExpect(status().isNoContent());
            refreshSigning.release();
            MvcResult staleResult = staleRefresh.get();
            assertEquals(409, staleResult.getResponse().getStatus());
            assertEquals("REFRESH_CONTEXT_CHANGED",
                    json(staleResult.getResponse().getContentAsByteArray()).get("code").asString());
            assertEquals(null, staleResult.getResponse().getHeader(HttpHeaders.SET_COOKIE));
            assertEquals(1, accessTokenIssuanceCount(switchFirst.identity().id()));

            MvcResult switchedRefresh = refresh(refreshToken(switchFirstSession), uuidV7(92_005))
                    .andExpect(status().isOk())
                    .andReturn();
            assertEquals(switchFirstTargetMembership.toString(),
                    tokenClaims(switchedRefresh).get("membershipId").asString());
            assertEquals(switchFirstTargetTenant.toString(),
                    tokenClaims(switchedRefresh).get("tenantId").asString());
        } finally {
            refreshSigning.release();
            switchFirstExecutor.shutdownNow();
        }
    }

    @Test
    @Order(35)
    void loginSelectionAndContextSwitchLoseTheRaceToANewFenceWithoutPersistingTokens() throws Exception {
        revocationIndexRecovery.recover();

        TestUser loginUser = createUser(
                "fence-race-login-complete@example.test", "correct-password", false, Credential.REGULAR);
        UUID loginMembership = uuidV7(94_001);
        UUID loginTenant = uuidV7(94_002);
        accessibleMemberships(loginUser.identity().id(), membership(loginMembership, loginTenant, "Login Race"));
        ConcurrencyGate loginSigning = new ConcurrencyGate();
        SIGNING_GATE.set(loginSigning);
        var loginExecutor = Executors.newSingleThreadExecutor();
        try {
            var loginResult = loginExecutor.submit(() -> login(
                    "fence-race-login-complete@example.test", "correct-password", "TENANT").andReturn());
            loginSigning.awaitEntered();
            revocationFenceService.establish(
                    uuidV7(94_003), RevocationFenceTarget.membership(loginMembership, loginTenant));
            loginSigning.release();

            MvcResult blocked = loginResult.get();
            assertEquals(403, blocked.getResponse().getStatus());
            assertEquals("ACCESS_CONTEXT_UNAVAILABLE",
                    json(blocked.getResponse().getContentAsByteArray()).get("code").asString());
            assertEquals(0, sessionFactCount(loginUser.identity().id()));
        } finally {
            loginSigning.release();
            SIGNING_GATE.set(null);
            loginExecutor.shutdownNow();
        }

        TestUser selectionUser = createUser(
                "fence-race-selection@example.test", "correct-password", false, Credential.REGULAR);
        UUID selectedMembership = uuidV7(94_011);
        UUID selectedTenant = uuidV7(94_012);
        UUID alternateMembership = uuidV7(94_013);
        UUID alternateTenant = uuidV7(94_014);
        accessibleMemberships(selectionUser.identity().id(),
                membership(selectedMembership, selectedTenant, "Selected"),
                membership(alternateMembership, alternateTenant, "Alternate"));
        MvcResult selectionSession = login(
                "fence-race-selection@example.test", "correct-password", "TENANT").andReturn();
        ConcurrencyGate selectionSigning = new ConcurrencyGate();
        SIGNING_GATE.set(selectionSigning);
        var selectionExecutor = Executors.newSingleThreadExecutor();
        try {
            var selectionResult = selectionExecutor.submit(
                    () -> selectContext(refreshToken(selectionSession), selectedMembership).andReturn());
            selectionSigning.awaitEntered();
            revocationFenceService.establish(
                    uuidV7(94_015), RevocationFenceTarget.membership(selectedMembership, selectedTenant));
            selectionSigning.release();

            MvcResult blocked = selectionResult.get();
            assertEquals(403, blocked.getResponse().getStatus());
            assertEquals("ACCESS_CONTEXT_UNAVAILABLE",
                    json(blocked.getResponse().getContentAsByteArray()).get("code").asString());
            assertEquals(0, accessTokenIssuanceCount(selectionUser.identity().id()));
        } finally {
            selectionSigning.release();
            SIGNING_GATE.set(null);
            selectionExecutor.shutdownNow();
        }

        TestUser switchUser = createUser(
                "fence-race-switch@example.test", "correct-password", false, Credential.REGULAR);
        UUID currentMembership = uuidV7(94_021);
        UUID currentTenant = uuidV7(94_022);
        UUID targetMembership = uuidV7(94_023);
        UUID targetTenant = uuidV7(94_024);
        accessibleMemberships(switchUser.identity().id(),
                membership(currentMembership, currentTenant, "Current"));
        MvcResult switchSession = login(
                "fence-race-switch@example.test", "correct-password", "TENANT").andReturn();
        accessibleMemberships(switchUser.identity().id(),
                membership(currentMembership, currentTenant, "Current"),
                membership(targetMembership, targetTenant, "Target"));
        ConcurrencyGate switchValidation = new ConcurrencyGate();
        MEMBERSHIP_VALIDATION_GATE.set(switchValidation);
        var switchExecutor = Executors.newSingleThreadExecutor();
        try {
            var switchResult = switchExecutor.submit(() -> mockMvc.perform(tenantSwitch(
                    refreshToken(switchSession), uuidV7(94_025), targetMembership)).andReturn());
            switchValidation.awaitEntered();
            revocationFenceService.establish(
                    uuidV7(94_026), RevocationFenceTarget.membership(targetMembership, targetTenant));
            switchValidation.release();

            MvcResult blocked = switchResult.get();
            assertEquals(403, blocked.getResponse().getStatus());
            assertEquals("ACCESS_CONTEXT_UNAVAILABLE",
                    json(blocked.getResponse().getContentAsByteArray()).get("code").asString());
            Map<String, Object> family = jdbc.queryForMap(
                    "SELECT membership_id, tenant_id FROM iam_refresh_token_families WHERE identity_id = ?",
                    switchUser.identity().id());
            assertEquals(currentMembership, family.get("membership_id"));
            assertEquals(currentTenant, family.get("tenant_id"));
            assertEquals(1, accessTokenIssuanceCount(switchUser.identity().id()));
        } finally {
            switchValidation.release();
            MEMBERSHIP_VALIDATION_GATE.set(null);
            switchExecutor.shutdownNow();
        }
    }

    @Test
    @Order(37)
    void batchRevocationRecoversAfterPostgresFailureAndReleaseNeverRevivesOldTokens() throws Exception {
        revocationIndexRecovery.recover();
        UUID targetTenant = uuidV7(95_001);
        UUID firstMembership = uuidV7(95_002);
        UUID secondMembership = uuidV7(95_003);
        TestUser first = createUser("batch-first@example.test", "correct-password", false, Credential.REGULAR);
        TestUser second = createUser("batch-second@example.test", "correct-password", false, Credential.REGULAR);
        accessibleMemberships(first.identity().id(), membership(firstMembership, targetTenant, "First"));
        accessibleMemberships(second.identity().id(), membership(secondMembership, targetTenant, "Second"));
        MvcResult firstSession = login("batch-first@example.test", "correct-password", "TENANT").andReturn();
        MvcResult secondSession = login("batch-second@example.test", "correct-password", "TENANT").andReturn();
        UUID firstJti = UUID.fromString(tokenClaims(firstSession).get("jti").asString());
        UUID secondJti = UUID.fromString(tokenClaims(secondSession).get("jti").asString());

        UUID isolatedTenant = uuidV7(95_011);
        UUID isolatedMembership = uuidV7(95_012);
        TestUser isolated = createUser("batch-isolated@example.test", "correct-password", false, Credential.REGULAR);
        accessibleMemberships(isolated.identity().id(),
                membership(isolatedMembership, isolatedTenant, "Isolated"));
        MvcResult isolatedSession = login(
                "batch-isolated@example.test", "correct-password", "TENANT").andReturn();
        UUID isolatedJti = UUID.fromString(tokenClaims(isolatedSession).get("jti").asString());

        UUID revocationRequestId = uuidV7(95_021);
        RevocationFenceTarget target = RevocationFenceTarget.tenant(targetTenant);
        setIamAppIssuanceUpdatePrivilege(false);
        try {
            assertThrows(RuntimeException.class,
                    () -> userSessionRevocationService.revoke(revocationRequestId, target));
        } finally {
            setIamAppIssuanceUpdatePrivilege(true);
        }

        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, firstJti));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, secondJti));
        int redisRejections = ("1".equals(redis.opsForValue().get(jtiRevocationKey(firstJti))) ? 1 : 0)
                + ("1".equals(redis.opsForValue().get(jtiRevocationKey(secondJti))) ? 1 : 0);
        assertEquals(1, redisRejections);
        var failed = userSessionRevocations.find(revocationRequestId).orElseThrow();
        assertEquals(UserSessionRevocationStatus.PENDING, failed.status());
        assertEquals("INTERNAL_RECOVERY_FAILURE", failed.lastFailure());
        assertEquals(1, failed.attemptCount());

        UserSessionRevocationResult completed = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            jdbc.update("UPDATE iam_user_session_revocations SET next_attempt_at = now() - interval '1 second' "
                    + "WHERE revocation_request_id = ?", revocationRequestId);
            completed = userSessionRevocationService.revoke(revocationRequestId, target);
            if (completed.status() == UserSessionRevocationResult.Status.COMPLETED) {
                break;
            }
        }
        assertNotNull(completed);
        assertEquals(UserSessionRevocationResult.Status.COMPLETED, completed.status());
        assertEquals(2, completed.revokedFamilyCount());
        assertEquals(2, completed.revokedJtiCount());
        assertEquals("1", redis.opsForValue().get(jtiRevocationKey(firstJti)));
        assertEquals("1", redis.opsForValue().get(jtiRevocationKey(secondJti)));
        assertEquals(null, redis.opsForValue().get(jtiRevocationKey(isolatedJti)));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, isolatedJti));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events WHERE event_snapshot ->> 'type' = "
                        + "'com.saas.forge.iam.sessions-revoked.v1' AND ordering_key = ?",
                Integer.class, revocationRequestId.toString()));

        UserSessionRevocationResult replay = userSessionRevocationService.revoke(revocationRequestId, target);
        assertEquals(completed, replay);
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events WHERE event_snapshot ->> 'type' = "
                        + "'com.saas.forge.iam.sessions-revoked.v1' AND ordering_key = ?",
                Integer.class, revocationRequestId.toString()));

        UUID releaseRequestId = uuidV7(95_022);
        userSessionRevocationService.release(releaseRequestId, revocationRequestId, target);
        assertEquals(null, redis.opsForValue().get(
                "sf:test:iam-service:user-session-revocation-fence:v1:tenant:" + targetTenant));
        assertEquals("1", redis.opsForValue().get(jtiRevocationKey(firstJti)));
        assertEquals("1", redis.opsForValue().get(jtiRevocationKey(secondJti)));

        UUID newerGeneration = uuidV7(95_023);
        revocationFenceService.establish(newerGeneration, target);
        userSessionRevocationService.release(releaseRequestId, revocationRequestId, target);
        assertEquals(newerGeneration.toString(), redis.opsForValue().get(
                "sf:test:iam-service:user-session-revocation-fence:v1:tenant:" + targetTenant));

        assertThrows(RevocationFenceConflictException.class, () -> revocationFenceService.establish(
                uuidV7(95_024), RevocationFenceTarget.membership(firstMembership, targetTenant)));
        assertTrue(revocationIndex.isUserTokenFenced(
                RevocationFenceTarget.membership(firstMembership, targetTenant)));
        assertFalse(revocationIndex.isUserTokenFenced(
                RevocationFenceTarget.membership(isolatedMembership, isolatedTenant)));
    }

    @Test
    @Order(99)
    void redisUnavailabilityFailsClosedThroughPublicContract() throws Exception {
        TestUser user = createUser("logout-redis-down@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult session = login("logout-redis-down@example.test", "correct-password", "PLATFORM").andReturn();
        TestUser refreshUser = createUser(
                "refresh-redis-down@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult refreshSession = login(
                "refresh-redis-down@example.test", "correct-password", "PLATFORM").andReturn();
        TestUser switchUser = createUser(
                "switch-redis-down@example.test", "correct-password", false, Credential.REGULAR);
        UUID switchCurrentMembership = uuidV7(52_002);
        UUID switchCurrentTenant = uuidV7(52_003);
        UUID switchTargetMembership = uuidV7(52_004);
        UUID switchTargetTenant = uuidV7(52_005);
        UUID switchKey = uuidV7(52_006);
        accessibleMemberships(switchUser.identity().id(),
                membership(switchCurrentMembership, switchCurrentTenant, "Current"));
        MvcResult switchSession = login(
                "switch-redis-down@example.test", "correct-password", "TENANT").andReturn();
        UUID switchJti = UUID.fromString(tokenClaims(switchSession).get("jti").asString());
        UUID switchFamilyId = jdbc.queryForObject(
                "SELECT family_id FROM iam_access_token_issuances WHERE jti = ?", UUID.class, switchJti);
        accessibleMemberships(switchUser.identity().id(),
                membership(switchCurrentMembership, switchCurrentTenant, "Current"),
                membership(switchTargetMembership, switchTargetTenant, "Target"));
        UUID jti = UUID.fromString(tokenClaims(session).get("jti").asString());
        REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).exec();
        try {
        refresh(refreshToken(refreshSession), uuidV7(52_001))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REFRESH_ROTATION_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertSessionUnchanged(refreshUser.identity().id(), 1);
        mockMvc.perform(tenantSwitch(refreshToken(switchSession), switchKey, switchTargetMembership))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_SWITCH_PENDING"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
        Map<String, Object> unchangedSwitchFamily = jdbc.queryForMap("""
                SELECT membership_id, tenant_id FROM iam_refresh_token_families WHERE identity_id = ?
                """, switchUser.identity().id());
        assertEquals(switchCurrentMembership, unchangedSwitchFamily.get("membership_id"));
        assertEquals(switchCurrentTenant, unchangedSwitchFamily.get("tenant_id"));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT switch_status FROM iam_tenant_context_switches WHERE idempotency_key = ?",
                String.class, switchKey));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events WHERE event_snapshot ->> 'subject' = ? "
                        + "AND event_snapshot ->> 'type' = 'com.saas.forge.iam.tenant-context-switched.v1'",
                Integer.class, switchFamilyId.toString()));
        logout(refreshToken(session), accessToken(session))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REVOCATION_INDEX_UNAVAILABLE"));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, user.identity().id()));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, jti));
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://platform.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"redis-down@example.test","password":"password","contextType":"PLATFORM"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_PROTECTION_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        } finally {
            REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
            var binding = REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec()
                    .getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(6379))[0];
            var factory = (LettuceConnectionFactory) redis.getConnectionFactory();
            factory.stop();
            factory.getStandaloneConfiguration().setPort(Integer.parseInt(binding.getHostPortSpec()));
            factory.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (true) {
                try {
                    ((LettuceConnectionFactory) redis.getConnectionFactory()).resetConnection();
                    revocationIndexRecovery.recover();
                    break;
                } catch (RevocationIndexUnavailableException | org.springframework.data.redis.RedisConnectionFailureException unavailable) {
                    if (System.nanoTime() >= deadline) throw unavailable;
                    Thread.sleep(100);
                }
            }
        }
    }

    @Test
    @Order(36)
    void revocationFencesAreDurableGenerationBoundAndBlockEveryTenantTokenIssuancePath() throws Exception {
        revocationIndexRecovery.recover();

        TestUser concurrentUser = createUser(
                "fence-race-login@example.test", "correct-password", false, Credential.REGULAR);
        UUID concurrentMembership = uuidV7(93_041);
        UUID concurrentTenant = uuidV7(93_042);
        accessibleMemberships(concurrentUser.identity().id(),
                membership(concurrentMembership, concurrentTenant, "Concurrent"));
        MvcResult concurrentSession = login(
                "fence-race-login@example.test", "correct-password", "TENANT").andReturn();
        ConcurrencyGate signing = new ConcurrencyGate();
        SIGNING_GATE.set(signing);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var concurrentRefresh = executor.submit(() -> refresh(
                    refreshToken(concurrentSession), uuidV7(93_044)).andReturn());
            signing.awaitEntered();
            revocationFenceService.establish(
                    uuidV7(93_043), RevocationFenceTarget.membership(concurrentMembership, concurrentTenant));
            signing.release();
            MvcResult blocked = concurrentRefresh.get();
            assertEquals(403, blocked.getResponse().getStatus());
            assertEquals("ACCESS_CONTEXT_UNAVAILABLE",
                    json(blocked.getResponse().getContentAsByteArray()).get("code").asString());
            assertSessionUnchanged(concurrentUser.identity().id(), 1);
        } finally {
            signing.release();
            SIGNING_GATE.set(null);
            executor.shutdownNow();
        }

        TestUser loginUser = createUser("fenced-login@example.test", "correct-password", false, Credential.REGULAR);
        UUID loginMembership = uuidV7(93_001);
        UUID loginTenant = uuidV7(93_002);
        UUID loginRequest = uuidV7(93_003);
        accessibleMemberships(loginUser.identity().id(), membership(loginMembership, loginTenant, "Login"));
        TestUser platformUser = createUser(
                "fence-ready-platform@example.test", "correct-password", true, Credential.REGULAR);

        redis.opsForValue().set("sf:test:iam-service:revocation-index-ready:v1:state", "0");
        login("fence-ready-platform@example.test", "correct-password", "PLATFORM")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REVOCATION_INDEX_UNAVAILABLE"));
        assertEquals(0, sessionFactCount(platformUser.identity().id()));
        login("fenced-login@example.test", "correct-password", "TENANT")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REVOCATION_INDEX_UNAVAILABLE"));
        assertEquals(0, sessionFactCount(loginUser.identity().id()));
        revocationIndexRecovery.recover();

        revocationFenceService.establish(loginRequest, RevocationFenceTarget.membership(loginMembership, loginTenant));

        login("fenced-login@example.test", "correct-password", "TENANT")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(0, sessionFactCount(loginUser.identity().id()));
        String loginFenceKey = "sf:test:iam-service:user-session-revocation-fence:v1:membership:"
                + loginMembership;
        assertEquals(loginRequest.toString(), redis.opsForValue().get(loginFenceKey));
        assertEquals(-1L, redis.getExpire(loginFenceKey));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT fence_status FROM iam_revocation_fences WHERE revocation_request_id = ?",
                String.class, loginRequest));
        RevocationFence originalGeneration = RevocationFence.establish(
                loginRequest, RevocationFenceTarget.membership(loginMembership, loginTenant), Instant.now());
        UUID newerGeneration = uuidV7(93_006);
        redis.opsForValue().set(loginFenceKey, newerGeneration.toString());
        assertFalse(revocationIndex.releaseFence(originalGeneration));
        assertEquals(newerGeneration.toString(), redis.opsForValue().get(loginFenceKey));
        redis.opsForValue().set(loginFenceKey, loginRequest.toString());
        assertTrue(revocationIndex.releaseFence(originalGeneration));
        assertEquals(null, redis.opsForValue().get(loginFenceKey));
        redis.opsForValue().set("sf:test:iam-service:revocation-index-ready:v1:state", "0");
        revocationIndexRecovery.recover();
        assertEquals(loginRequest.toString(), redis.opsForValue().get(loginFenceKey));
        assertEquals("1", redis.opsForValue().get("sf:test:iam-service:revocation-index-ready:v1:state"));
        assertThrows(RevocationFenceConflictException.class, () -> revocationFenceService.establish(
                loginRequest, RevocationFenceTarget.tenant(uuidV7(93_004))));
        assertThrows(RevocationFenceConflictException.class, () -> revocationFenceService.establish(
                uuidV7(93_005), RevocationFenceTarget.membership(loginMembership, loginTenant)));
        assertEquals(loginRequest.toString(), redis.opsForValue().get(loginFenceKey));

        TestUser selectionUser = createUser(
                "fenced-selection@example.test", "correct-password", false, Credential.REGULAR);
        UUID selectedMembership = uuidV7(93_011);
        UUID selectedTenant = uuidV7(93_012);
        UUID otherMembership = uuidV7(93_013);
        UUID otherTenant = uuidV7(93_014);
        accessibleMemberships(selectionUser.identity().id(),
                membership(selectedMembership, selectedTenant, "Selected"),
                membership(otherMembership, otherTenant, "Other"));
        MvcResult selection = login(
                "fenced-selection@example.test", "correct-password", "TENANT").andReturn();
        revocationFenceService.establish(
                uuidV7(93_015), RevocationFenceTarget.membership(selectedMembership, selectedTenant));
        selectContext(refreshToken(selection), selectedMembership)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"));
        assertEquals(0, accessTokenIssuanceCount(selectionUser.identity().id()));

        TestUser refreshUser = createUser(
                "fenced-refresh@example.test", "correct-password", false, Credential.REGULAR);
        UUID refreshMembership = uuidV7(93_021);
        UUID refreshTenant = uuidV7(93_022);
        accessibleMemberships(refreshUser.identity().id(), membership(refreshMembership, refreshTenant, "Refresh"));
        MvcResult refreshSession = login(
                "fenced-refresh@example.test", "correct-password", "TENANT").andReturn();
        revocationFenceService.establish(uuidV7(93_023), RevocationFenceTarget.tenant(refreshTenant));
        refresh(refreshToken(refreshSession), uuidV7(93_024))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"));
        assertSessionUnchanged(refreshUser.identity().id(), 1);

        TestUser switchUser = createUser(
                "fenced-switch@example.test", "correct-password", false, Credential.REGULAR);
        UUID currentMembership = uuidV7(93_031);
        UUID currentTenant = uuidV7(93_032);
        UUID targetMembership = uuidV7(93_033);
        UUID targetTenant = uuidV7(93_034);
        accessibleMemberships(switchUser.identity().id(), membership(currentMembership, currentTenant, "Current"));
        MvcResult switchSession = login(
                "fenced-switch@example.test", "correct-password", "TENANT").andReturn();
        accessibleMemberships(switchUser.identity().id(),
                membership(currentMembership, currentTenant, "Current"),
                membership(targetMembership, targetTenant, "Target"));
        mockMvc.perform(tenantSwitch(refreshToken(switchSession), uuidV7(93_035), targetMembership))
                .andExpect(status().isNoContent());
        revocationFenceService.establish(
                uuidV7(93_036), RevocationFenceTarget.membership(targetMembership, targetTenant));
        refresh(refreshToken(switchSession), uuidV7(93_037))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"));
        assertEquals(1, accessTokenIssuanceCount(switchUser.identity().id()));
    }

    @Test
    @Order(34)
    void anonymousPasswordSetupAcceptsOnlySecretBodyAndCreatesNoSession() throws Exception {
        TestUser user = createUser("password-setup-http@example.test", "unused", false, Credential.NONE);
        PasswordSetupChallengeToken challenge = passwordSetupService.issueChallenge(user.identity().id());

        mockMvc.perform(post("/api/v1/auth/password-setups")
                        .header("Idempotency-Key", uuidV7(53_001).toString())
                        .header("X-SF-CSRF", "1")
                        .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "token", challenge.value(),
                                "newPassword", "HTTP-Setup-Password-2026"))))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertEquals(0, sessionFactCount(user.identity().id()));
        assertEquals(1, identities.findCredentials(user.identity().id()).size());

        TestUser rejected = createUser("password-setup-extra-field@example.test", "unused", false, Credential.NONE);
        PasswordSetupChallengeToken rejectedChallenge = passwordSetupService.issueChallenge(rejected.identity().id());
        mockMvc.perform(post("/api/v1/auth/password-setups")
                        .header("Idempotency-Key", uuidV7(53_002).toString())
                        .header("X-SF-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "token", rejectedChallenge.value(),
                                "newPassword", "HTTP-Setup-Password-2026",
                                "email", "forged@example.test"))))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertTrue(identities.findCredentials(rejected.identity().id()).isEmpty());

        mockMvc.perform(post("/api/v1/auth/password-setups")
                        .header("Idempotency-Key", uuidV7(53_003).toString())
                        .header("X-SF-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\","
                                + "\"newPassword\":\"HTTP-Setup-Password-2026\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_SETUP_TOKEN_INVALID"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @Order(35)
    void platformAndTenantBrowserSessionSlotsRemainIndependent() throws Exception {
        TestUser user = createUser("dual-slot@example.test", "correct-password", true, Credential.REGULAR);
        UUID membershipId = uuidV7(95_001);
        UUID tenantId = uuidV7(95_002);
        accessibleMemberships(user.identity().id(), membership(membershipId, tenantId, "Dual Slot Tenant"));

        MvcResult platformLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://platform.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .cookie(new Cookie("__Host-sf_refresh", "legacy-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dual-slot@example.test","password":"correct-password",
                                 "contextType":"PLATFORM"}
                                """))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("__Host-sf_platform_refresh"))
                .andExpect(cookie().doesNotExist("__Host-sf_tenant_refresh"))
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("__Host-sf_refresh="),
                                org.hamcrest.Matchers.containsString("Max-Age=0")))))
                .andReturn();
        String platformRefresh = platformLogin.getResponse()
                .getCookie("__Host-sf_platform_refresh").getValue();

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://platform.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .cookie(new Cookie("__Host-sf_platform_refresh", platformRefresh))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dual-slot@example.test","password":"wrong",
                                 "contextType":"PLATFORM"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_SLOT_ALREADY_ACTIVE"));

        MvcResult tenantLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://console.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .cookie(new Cookie("__Host-sf_platform_refresh", platformRefresh))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dual-slot@example.test","password":"correct-password",
                                 "contextType":"TENANT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("__Host-sf_tenant_refresh"))
                .andReturn();
        String tenantRefresh = tenantLogin.getResponse().getCookie("__Host-sf_tenant_refresh").getValue();

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://console.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dual-slot@example.test","password":"correct-password",
                                 "contextType":"PLATFORM"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BROWSER_REQUEST_REJECTED"));

        MvcResult refreshedPlatform = mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Idempotency-Key", uuidV7(95_003).toString())
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://platform.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .cookie(new Cookie("__Host-sf_platform_refresh", platformRefresh))
                        .cookie(new Cookie("__Host-sf_tenant_refresh", tenantRefresh))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionSlot\":\"PLATFORM\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("__Host-sf_platform_refresh"))
                .andReturn();

        MvcResult refreshedTenant = mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Idempotency-Key", uuidV7(95_004).toString())
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://console.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .cookie(new Cookie("__Host-sf_platform_refresh", platformRefresh))
                        .cookie(new Cookie("__Host-sf_tenant_refresh", tenantRefresh))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionSlot\":\"TENANT\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("__Host-sf_tenant_refresh"))
                .andReturn();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://platform.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .cookie(new Cookie("__Host-sf_platform_refresh", refreshedPlatform.getResponse()
                                .getCookie("__Host-sf_platform_refresh").getValue()))
                        .cookie(new Cookie("__Host-sf_tenant_refresh", refreshedTenant.getResponse()
                                .getCookie("__Host-sf_tenant_refresh").getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionSlot\":\"PLATFORM\"}"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("__Host-sf_platform_refresh", 0));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Idempotency-Key", uuidV7(95_005).toString())
                        .header("X-SF-CSRF", "1")
                        .header("Origin", "https://console.saas.forge.test")
                        .header("Sec-Fetch-Site", "same-site")
                        .cookie(new Cookie("__Host-sf_tenant_refresh", refreshedTenant.getResponse()
                                .getCookie("__Host-sf_tenant_refresh").getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionSlot\":\"TENANT\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions selectContext(String refreshToken, UUID membershipId)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/context-selections")
                .header("X-SF-CSRF", "1")
                .header("Origin", "https://console.saas.forge.test")
                .header("Sec-Fetch-Site", "same-site")
                .cookie(new Cookie("__Host-sf_tenant_refresh", refreshToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(Map.of("membershipId", membershipId))));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tenantSwitch(
            String refreshToken, UUID idempotencyKey, UUID membershipId) throws Exception {
        return post("/api/v1/auth/tenant-switches")
                .header("Idempotency-Key", idempotencyKey.toString())
                .header("X-SF-CSRF", "1")
                .header("Origin", "https://console.saas.forge.test")
                .header("Sec-Fetch-Site", "same-site")
                .cookie(new Cookie("__Host-sf_tenant_refresh", refreshToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(Map.of("membershipId", membershipId)));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
        return refresh(refreshToken, UUID.fromString(IDEMPOTENCY_KEY));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken, UUID idempotencyKey)
            throws Exception {
        String sessionSlot = sessionSlotFor(refreshToken);
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Idempotency-Key", idempotencyKey.toString())
                .header("X-SF-CSRF", "1")
                .header("Origin", originFor(sessionSlot))
                .header("Sec-Fetch-Site", "same-site")
                .cookie(new Cookie(cookieNameFor(sessionSlot), refreshToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionSlot\":\"" + sessionSlot + "\"}"));
    }

    private List<MvcResult> concurrentRefreshes(String refreshToken, UUID firstKey, UUID secondKey) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentRefresh(refreshToken, firstKey, ready, start));
            var second = executor.submit(() -> concurrentRefresh(refreshToken, secondKey, ready, start));
            ready.await();
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private MvcResult concurrentRefresh(
            String refreshToken, UUID idempotencyKey, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return refresh(refreshToken, idempotencyKey).andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions logout(String refreshToken, String accessToken)
            throws Exception {
        String sessionSlot = sessionSlotFor(refreshToken);
        return mockMvc.perform(post("/api/v1/auth/logout")
                .header("X-SF-CSRF", "1")
                .header("Origin", originFor(sessionSlot))
                .header("Sec-Fetch-Site", "same-site")
                .header("Authorization", "Bearer " + accessToken)
                .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                .cookie(new Cookie(cookieNameFor(sessionSlot), refreshToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionSlot\":\"" + sessionSlot + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions changePassword(String refreshToken, String newPassword)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-changes")
                .header("X-SF-CSRF", "1")
                .header("Origin", "https://platform.saas.forge.test")
                .header("Sec-Fetch-Site", "same-site")
                .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                .cookie(new Cookie("__Host-sf_platform_refresh", refreshToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(Map.of("newPassword", newPassword))));
    }

    private static String refreshToken(MvcResult response) {
        String setCookie = response.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        Matcher matcher = COOKIE_VALUE.matcher(setCookie);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password, String contextType)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .header("X-SF-CSRF", "1")
                .header("Origin", originFor(contextType))
                .header("Sec-Fetch-Site", "same-site")
                .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(Map.of(
                        "email", email, "password", password, "contextType", contextType))));
    }

    private org.springframework.test.web.servlet.ResultActions loginWithoutContext(String email, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .header("X-SF-CSRF", "1")
                .header("Origin", "https://console.saas.forge.test")
                .header("Sec-Fetch-Site", "same-site")
                .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(Map.of("email", email, "password", password))));
    }

    private String sessionSlotFor(String refreshToken) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(refreshToken.getBytes(StandardCharsets.UTF_8));
        String purpose = jdbc.queryForObject("""
                SELECT family.family_purpose
                FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE token.token_digest = ?
                """, String.class, digest);
        return switch (purpose) {
            case "USER_PLATFORM", "INITIAL_PASSWORD_CHANGE" -> "PLATFORM";
            case "USER_TENANT", "USER_TENANT_SELECTION" -> "TENANT";
            default -> throw new IllegalStateException("Unsupported family purpose: " + purpose);
        };
    }

    private static String cookieNameFor(String sessionSlot) {
        return "PLATFORM".equals(sessionSlot)
                ? "__Host-sf_platform_refresh"
                : "__Host-sf_tenant_refresh";
    }

    private static String originFor(String sessionSlot) {
        return "PLATFORM".equals(sessionSlot)
                ? "https://platform.saas.forge.test"
                : "https://console.saas.forge.test";
    }

    private static JsonNode tokenClaims(MvcResult response) {
        return json(Base64.getUrlDecoder().decode(accessToken(response).split("\\.")[1]));
    }

    private static String accessToken(MvcResult response) {
        return json(response.getResponse().getContentAsByteArray()).get("accessToken").asString();
    }

    private static String jtiRevocationKey(UUID jti) throws Exception {
        String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(jti.toString().getBytes(StandardCharsets.UTF_8)));
        return "sf:test:iam-service:jwt-jti-revocation:v1:" + digest;
    }

    private static String oauthClientRevocationKey(UUID clientId) {
        return "sf:test:iam-service:oauth-client-revocation:v1:" + clientId;
    }

    private static String refreshRotationLeaseKey(String refreshToken) throws Exception {
        String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
        return "sf:test:iam-service:refresh-rotation-lease:v1:" + digest;
    }

    private static void setIamAppIssuanceUpdatePrivilege(boolean granted) throws Exception {
        String sql = (granted ? "GRANT" : "REVOKE")
                + " UPDATE ON TABLE iam_access_token_issuances "
                + (granted ? "TO" : "FROM") + " iam_app";
        try (Connection connection = DriverManager.getConnection(
                iamJdbcUrl(), "iam_migrator", "iam-migrator-password")) {
            connection.createStatement().execute(sql);
        }
    }

    private static String basic(UUID clientId, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static String serviceClientSecret(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void accessibleMemberships(
            UUID identityId,
            io.saas.forge.contracts.tenantaccess.membership.v1.AccessibleMembership... memberships) {
        try (Connection connection = tenantAccessMigratorConnection()) {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM memberships WHERE identity_id = ?")) {
                delete.setObject(1, identityId);
                delete.executeUpdate();
            }
            for (var membership : memberships) {
                UUID tenantId = UUID.fromString(membership.getTenantId());
                try (PreparedStatement deleteReusedId = connection.prepareStatement(
                        "DELETE FROM memberships WHERE id = ?")) {
                    deleteReusedId.setObject(1, UUID.fromString(membership.getMembershipId()));
                    deleteReusedId.executeUpdate();
                }
                try (PreparedStatement tenant = connection.prepareStatement("""
                        INSERT INTO tenants (id, display_name, tenant_status)
                        VALUES (?, ?, 'ACTIVE')
                        ON CONFLICT (id) DO UPDATE
                        SET display_name = EXCLUDED.display_name, tenant_status = 'ACTIVE', expires_at = NULL
                        """)) {
                    tenant.setObject(1, tenantId);
                    tenant.setString(2, membership.getTenantDisplayName());
                    tenant.executeUpdate();
                }
                try (PreparedStatement storedMembership = connection.prepareStatement("""
                        INSERT INTO memberships (id, tenant_id, identity_id, membership_status)
                        VALUES (?, ?, ?, 'ENABLED')
                        """)) {
                    storedMembership.setObject(1, UUID.fromString(membership.getMembershipId()));
                    storedMembership.setObject(2, tenantId);
                    storedMembership.setObject(3, identityId);
                    storedMembership.executeUpdate();
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("无法准备 Tenant Access Membership 权威数据", exception);
        }
    }

    private static io.saas.forge.contracts.tenantaccess.membership.v1.AccessibleMembership membership(
            UUID membershipId, UUID tenantId, String displayName) {
        return io.saas.forge.contracts.tenantaccess.membership.v1.AccessibleMembership.newBuilder()
                .setMembershipId(membershipId.toString())
                .setTenantId(tenantId.toString())
                .setTenantDisplayName(displayName)
                .build();
    }

    private static void tenantBrandProfile(
            UUID tenantId,
            String displayName,
            String logoUrl,
            String faviconUrl,
            String primaryColor,
            String accentColor) {
        try (Connection connection = tenantAccessMigratorConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO tenant_brand_profiles (
                            tenant_id, display_name, logo_url, favicon_url, primary_color, accent_color
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setObject(1, tenantId);
            statement.setString(2, displayName);
            statement.setString(3, logoUrl);
            statement.setString(4, faviconUrl);
            statement.setString(5, primaryColor);
            statement.setString(6, accentColor);
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("无法准备 Tenant Brand Profile 权威数据", exception);
        }
    }

    private static UUID uuidV7(long suffix) {
        return UUID.fromString(String.format("0198c9d5-0f25-7000-8000-%012x", suffix));
    }

    private void assertAuthenticationFailed(MvcResult result) {
        assertEquals(401, result.getResponse().getStatus());
        JsonNode problem = json(result.getResponse().getContentAsByteArray());
        assertEquals("AUTHENTICATION_FAILED", problem.get("code").asString());
        assertEquals("邮箱或密码无效", problem.get("detail").asString());
        assertEquals(null, result.getResponse().getHeader("Set-Cookie"));
    }

    private TestUser createUser(String email, String password, boolean withRole, Credential credential) {
        Instant now = Instant.now();
        Identity identity = identities.create(Identity.register(email, null, now));
        if (credential == Credential.REGULAR) {
            identities.create(PasswordCredential.regular(identity.id(), passwordHash(password), now));
        } else if (credential == Credential.ACTIVE_INITIAL) {
            identities.create(PasswordCredential.initial(identity.id(), passwordHash(password), now));
        } else if (credential == Credential.EXPIRED_INITIAL) {
            identities.create(PasswordCredential.initial(
                    identity.id(), passwordHash(password), now.minus(Duration.ofHours(25))));
        }
        if (withRole) {
            platformRoles.grant(PlatformRoleAssignment.grant(
                    identity.id(), "PLATFORM_ADMIN", now.minusSeconds(1)));
        }
        return new TestUser(identity);
    }

    private int sessionFactCount(UUID identityId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM iam_refresh_token_families WHERE identity_id = ?", Integer.class, identityId);
    }

    private int consumedRefreshTokenCount(UUID identityId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_tokens token
                JOIN iam_refresh_token_families family ON family.id = token.family_id
                WHERE family.identity_id = ? AND token.consumed_at IS NOT NULL
                """, Integer.class, identityId);
    }

    private int activeRefreshTokenCount(UUID identityId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_tokens token
                JOIN iam_refresh_token_families family ON family.id = token.family_id
                WHERE family.identity_id = ? AND token.consumed_at IS NULL
                """, Integer.class, identityId);
    }

    private int accessTokenIssuanceCount(UUID identityId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM iam_access_token_issuances WHERE identity_id = ?", Integer.class, identityId);
    }

    private void assertSessionUnchanged(UUID identityId, int expectedIssuances) {
        assertEquals(0, consumedRefreshTokenCount(identityId));
        assertEquals(1, activeRefreshTokenCount(identityId));
        assertEquals(expectedIssuances, accessTokenIssuanceCount(identityId));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, identityId));
    }

    private static Argon2idPasswordHash passwordHash(String password) {
        return Argon2idPasswordHash.of(new Argon2PasswordEncoder(16, 32, 1, 19_456, 2).encode(password));
    }

    private static ConsumerRecord<String, String> awaitEvent(KafkaConsumer<String, String> consumer) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            var records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("未收到 session.started 事件");
    }

    private static KafkaConsumer<String, String> kafkaConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "iam-login-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(properties);
    }

    private static JsonNode json(byte[] value) {
        return new ObjectMapper().readTree(value);
    }

    private static void migrateAndSeedSigningKey() {
        Flyway.configure()
                .dataSource(iamJdbcUrl(), "iam_migrator", "iam-migrator-password")
                .locations("filesystem:" + REPOSITORY_ROOT
                        .resolve("saas-forge-services/iam-service/src/main/resources/db/migration"))
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(
                iamJdbcUrl(), "iam_migrator", "iam-migrator-password");
                PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO iam_signing_keys
                        (kid, key_version_reference, public_jwk_modulus, public_jwk_exponent,
                         key_status, published_at, activated_at)
                    VALUES ('active-login-kid', 'test/key/1', ?, ?,
                            'ACTIVE', now() - interval '10 minutes', now() - interval '5 minutes')
                    """)) {
            statement.setString(1, SIGNING_KEY.getModulus().toString());
            statement.setString(2, SIGNING_KEY.getPublicExponent().toString());
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("无法准备 IAM 登录集成测试", exception);
        }
    }

    private static void migrateTenantAccess() {
        Flyway.configure()
                .dataSource(tenantAccessJdbcUrl(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("filesystem:" + REPOSITORY_ROOT
                        .resolve("saas-forge-services/tenant-access-service/src/main/resources/db/migration"))
                .load()
                .migrate();
    }

    private static AccessibleMembershipMapper tenantAccessMembershipMapper() {
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    tenantAccessJdbcUrl(), "tenant_access_app", "tenant-access-app-password");
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/AccessibleMembershipMapper.xml"));
            factory.setTypeHandlersPackage("io.saas.forge.tenantaccess.infrastructure.persistence.type");
            return new SqlSessionTemplate(factory.getObject()).getMapper(AccessibleMembershipMapper.class);
        } catch (Exception exception) {
            throw new IllegalStateException("无法装配 Tenant Access Membership Repository", exception);
        }
    }

    private static Optional<ServiceJwtVerificationKey> verificationKey(String kid) {
        if (!SIGNING_KEY.getKeyID().equals(kid)) {
            return Optional.empty();
        }
        return Optional.of(new ServiceJwtVerificationKey(
                SIGNING_KEY.getKeyID(),
                SIGNING_KEY.getModulus().toString(),
                SIGNING_KEY.getPublicExponent().toString()));
    }

    private static RSAKey signingKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("active-login-kid").generate();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成集成测试签名密钥", exception);
        }
    }

    private static Path secretFile(String prefix, String value) {
        try {
            Path path = Files.createTempFile(prefix, ".secret");
            Files.writeString(path, value, StandardCharsets.UTF_8);
            path.toFile().deleteOnExit();
            return path;
        } catch (Exception exception) {
            throw new IllegalStateException("无法准备集成测试外部 Secret 文件", exception);
        }
    }

    private void ensureReservedIamServiceClient() {
        if (oauthClients.findById(IAM_SERVICE_CLIENT_ID).isPresent()) {
            return;
        }
        Instant issuedAt = Instant.now().minusSeconds(1);
        oauthClients.createWithId(
                OAuthClient.register(
                                "iam-service",
                                Set.of(OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ),
                                issuedAt)
                        .identifiedBy(IAM_SERVICE_CLIENT_ID),
                ClientSecretDigest.fromPlaintext(IAM_SERVICE_CLIENT_SECRET),
                issuedAt);
    }

    private org.springframework.test.web.servlet.ResultActions rotateClient(
            String platformToken, UUID clientId, UUID idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/platform/oauth-clients/{clientId}/secret-rotations", clientId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header("Idempotency-Key", idempotencyKey.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.clientId").value(clientId.toString()))
                .andExpect(jsonPath("$.clientSecret").isString());
    }

    private void createReservedClientIfMissing(
            UUID clientId,
            String displayName,
            Set<OAuthScope> scopes,
            byte secretSeed,
            Instant issuedAt) {
        if (oauthClients.findById(clientId).isPresent()) return;
        oauthClients.createWithId(
                OAuthClient.register(displayName, scopes, issuedAt).identifiedBy(clientId),
                ClientSecretDigest.fromPlaintext(serviceClientSecret(secretSeed)), issuedAt);
    }

    private static Set<String> scopeValues(JsonNode response) {
        Set<String> values = new java.util.HashSet<>();
        response.get("allowedScopes").forEach(scope -> values.add(scope.asString()));
        return values;
    }

    private static String iamJdbcUrl() {
        return POSTGRES.getJdbcUrl().replace("/saas.forge", "/iam_db");
    }

    private static String tenantAccessJdbcUrl() {
        return POSTGRES.getJdbcUrl().replace("/saas.forge", "/tenant_access_db");
    }

    private static Connection tenantAccessMigratorConnection() throws Exception {
        return DriverManager.getConnection(
                tenantAccessJdbcUrl(), "tenant_access_migrator", "tenant-access-migrator-password");
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("deploy/postgresql/bootstrap.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位仓库根目录");
    }

    enum Credential { REGULAR, ACTIVE_INITIAL, EXPIRED_INITIAL, NONE }

    record TestUser(Identity identity) { }

    static final class ConcurrencyGate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void pause() {
            entered.countDown();
            try {
                if (!released.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发验收栅栏等待超时");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并发验收栅栏被中断", exception);
            }
        }

        void awaitEntered() {
            try {
                if (!entered.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发验收请求未进入预期接缝");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待并发验收请求时被中断", exception);
            }
        }

        void release() {
            released.countDown();
        }
    }

    static final class FaultInjectingAccessibleMembershipService
            extends AccessibleMembershipQueryServiceGrpc.AccessibleMembershipQueryServiceImplBase {
        private final AccessibleMembershipGrpcService delegate;

        FaultInjectingAccessibleMembershipService(AccessibleMembershipGrpcService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void listAccessibleMemberships(
                ListAccessibleMembershipsRequest request,
                StreamObserver<ListAccessibleMembershipsResponse> responseObserver) {
            UUID identityId = UUID.fromString(request.getIdentityId());
            if (TENANT_ACCESS_FAILURES.contains(identityId)) {
                responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
                return;
            }
            delegate.listAccessibleMemberships(request, responseObserver);
        }
    }

    static final class FaultInjectingMembershipValidationService
            extends MembershipValidationServiceGrpc.MembershipValidationServiceImplBase {
        private final MembershipValidationGrpcService delegate;

        FaultInjectingMembershipValidationService(MembershipValidationGrpcService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void validateMembership(
                ValidateMembershipRequest request,
                StreamObserver<ValidateMembershipResponse> responseObserver) {
            UUID identityId = UUID.fromString(request.getIdentityId());
            if (TENANT_ACCESS_FAILURES.contains(identityId)) {
                responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
                return;
            }
            ConcurrencyGate gate = MEMBERSHIP_VALIDATION_GATE.getAndSet(null);
            if (gate != null) {
                gate.pause();
            }
            delegate.validateMembership(request, responseObserver);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableScheduling
    @EnableTransactionManagement
    @MapperScan(basePackages = "io.saas.forge.iam.infrastructure.persistence.mapper",
            sqlSessionFactoryRef = "iamSqlSessionFactory")
    @ComponentScan(basePackageClasses = {
            MyBatisIdentityRepository.class,
            AuthenticationController.class,
            OutboxPublisher.class
    }, excludeFilters = @ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.ANNOTATION,
            classes = org.springframework.boot.test.context.TestConfiguration.class))
    @Import({AuthenticationConfiguration.class, PasswordSetupMailConfiguration.class,
            io.saas.forge.iam.config.OAuthClientManagementConfiguration.class})
    static class TestConfiguration {
        @Bean
        @org.springframework.context.annotation.Scope("prototype")
        ReservedIamServiceAccessTokenProvider reservedServiceTokens(
                io.saas.forge.iam.application.authentication.ClientCredentialsTokenService tokens) {
            return new ReservedIamServiceAccessTokenProvider(tokens, IAM_SERVICE_CLIENT_ID_FILE,
                    IAM_SERVICE_CLIENT_SECRET_FILE, java.time.Clock.systemUTC());
        }

        @Bean
        static ConversionService conversionService() {
            return ApplicationConversionService.getSharedInstance();
        }

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(iamJdbcUrl(), "iam_app", "iam-app-password");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory iamSqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/*Mapper.xml"));
            factory.setTypeHandlersPackage("io.saas.forge.iam.infrastructure.persistence.type");
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory iamSqlSessionFactory) {
            return new SqlSessionTemplate(iamSqlSessionFactory);
        }

        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                    REDIS.getHost(), REDIS.getMappedPort(6379));
            configuration.setPassword(RedisPassword.of(REDIS_PASSWORD));
            return new LettuceConnectionFactory(configuration);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory redisConnectionFactory) {
            return new StringRedisTemplate(redisConnectionFactory);
        }

        @Bean
        KafkaTemplate<String, String> kafkaTemplate() {
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                    org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all",
                    org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
        }

        @Bean
        JsonMapper objectMapper() {
            return JsonMapper.builder()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();
        }

        @Bean
        WebMvcConfigurer strictJsonWebMvc(JsonMapper objectMapper) {
            return new WebMvcConfigurer() {
                @Override
                public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                    converters.removeIf(JacksonJsonHttpMessageConverter.class::isInstance);
                    converters.add(0, new JacksonJsonHttpMessageConverter(objectMapper));
                }
            };
        }

        @Bean
        AccessibleMemberships accessibleMemberships() {
            return new GrpcAccessibleMemberships(
                    AccessibleMembershipQueryServiceGrpc.newBlockingStub(TENANT_ACCESS_CHANNEL));
        }

        @Bean
        MembershipValidation membershipValidation() {
            return new GrpcMembershipValidation(
                    MembershipValidationServiceGrpc.newBlockingStub(TENANT_ACCESS_CHANNEL),
                    () -> SERVICE_TOKENS.get().membershipReadToken());
        }

        @Bean
        JwtSigningPort jwtSigningPort() {
            return (keyReference, algorithm, signingInput) -> {
                try {
                    SERVICE_SIGNING_TRANSACTION.set(org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive());
                    if (SIGNING_FAILURE.get()) {
                        throw new IllegalStateException("injected signing failure");
                    }
                    ConcurrencyGate gate = SIGNING_GATE.getAndSet(null);
                    if (gate != null) {
                        gate.pause();
                    }
                    Signature signature = Signature.getInstance("SHA256withRSA");
                    signature.initSign(SIGNING_KEY.toPrivateKey());
                    signature.update(signingInput.bytes());
                    return signature.sign();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            };
        }

        @Bean
        JwtSigningService jwtSigningService(SigningKeyRepository repository, JwtSigningPort signingPort) {
            return new JwtSigningService(new ActiveSigningKeyResolver(repository), signingPort);
        }

        @Bean
        SigningKeyRevocationTransaction signingKeyRevocationTransaction(
                SigningKeyRepository signingKeys, AccessTokenIssuanceRepository issuances) {
            return new SigningKeyRevocationTransaction(signingKeys, issuances);
        }

        @Bean
        SigningKeyLifecycleService signingKeyLifecycleService(
                SigningKeyRepository signingKeys,
                AccessTokenIssuanceRepository issuances,
                RevocationIndex revocationIndex,
                SigningKeyRevocationTransaction transaction,
                java.time.Clock clock) {
            return new SigningKeyLifecycleService(signingKeys, issuances, revocationIndex, transaction, clock);
        }

        @Bean
        PresentedAccessTokenVerifier presentedAccessTokenVerifier() {
            return authorization -> {
                if (authorization == null || !authorization.startsWith("Bearer ")) {
                    return Optional.empty();
                }
                try {
                    String[] segments = authorization.substring(7).split("\\.");
                    JsonNode header = json(Base64.getUrlDecoder().decode(segments[0]));
                    JsonNode claims = json(Base64.getUrlDecoder().decode(segments[1]));
                    return Optional.of(new PresentedAccessToken(
                            UUID.fromString(claims.get("jti").asString()),
                            header.get("kid").asString(),
                            Instant.ofEpochSecond(claims.get("exp").asLong())));
                } catch (RuntimeException invalidToken) {
                    return Optional.empty();
                }
            };
        }

    }
}
