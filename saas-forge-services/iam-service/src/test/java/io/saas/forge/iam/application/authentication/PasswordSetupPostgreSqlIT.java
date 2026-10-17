package io.saas.forge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saas.forge.iam.domain.identity.Identity;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.identity.PasswordSetupChallengeRepository;
import io.saas.forge.iam.domain.outbox.ClaimedOutboxEvent;
import io.saas.forge.iam.domain.outbox.OutboxEvent;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.infrastructure.persistence.MyBatisIdentityRepository;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import io.saas.forge.iam.infrastructure.mail.SmtpPasswordSetupMailer;

@Testcontainers
@SpringJUnitConfig(PasswordSetupPostgreSqlIT.TestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordSetupPostgreSqlIT {
    private static final Instant NOW = Instant.parse("2026-08-22T06:30:00Z");
    private static final UUID KEY_A = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4e01");
    private static final UUID KEY_B = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4e02");
    private static final Path REPOSITORY_ROOT = repositoryRoot();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
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
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.30.7"))
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/info").forPort(8025));

    static {
        POSTGRES.start();
    }

    @Autowired private PasswordSetupService service;
    @Autowired @Qualifier("failingPasswordSetupService") private PasswordSetupService failingService;
    @Autowired private IdentityRepository identities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordSetupDeliveryTransaction deliveryTransaction;

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(iamJdbcUrl(), "iam_migrator", "iam-migrator-password")
                .locations("filesystem:" + REPOSITORY_ROOT.resolve(
                        "saas-forge-services/iam-service/src/main/resources/db/migration"))
                .load()
                .migrate();
    }

    @Autowired
    private io.saas.forge.iam.domain.identity.PasswordSetupDeliveryRepository deliveries;

    @Autowired
    private io.saas.forge.iam.domain.client.OAuthClientRepository clients;

    @Test
    void notificationReadDistinguishesPendingAndSmtpAcceptanceWithoutSending() {
        UUID identityId = createIdentity("notification-read@example.test");
        ensureTenantAccessClient();
        var query = new PasswordSetupNotificationQueryService(deliveries, identities, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(PasswordSetupNotificationQueryService.State.NOT_REQUESTED, query.get(KEY_A, KEY_B, identityId));
        var challenge = service.issueChallenge(identityId);
        deliveries.savePending(KEY_A, KEY_B, identityId, challenge.challengeId(), challenge.expiresAt());
        assertEquals(PasswordSetupNotificationQueryService.State.PENDING, query.get(KEY_A, KEY_B, identityId));
        deliveries.markDelivered(KEY_A, KEY_B, challenge.challengeId(), NOW);
        assertEquals(PasswordSetupNotificationQueryService.State.MAIL_SERVICE_ACCEPTED, query.get(KEY_A, KEY_B, identityId));
        service.establishPassword(KEY_B, challenge.value(), "Notification-Password-2026", null);
        assertEquals(PasswordSetupNotificationQueryService.State.PASSWORD_READY, query.get(KEY_A, KEY_B, identityId));
        identities.invalidate(identities.findCredentials(identityId).get(0).id(), NOW);
        assertEquals(PasswordSetupNotificationQueryService.State.RECOVERY_REQUIRED, query.get(KEY_A, KEY_B, identityId));
    }

    @Test
    void invitationSetupUsesCurrentCredentialAndPersistsOnlyRealSmtpDelivery() throws Exception {
        UUID identityId = createIdentity("invited-first-setup@example.test");
        ensureTenantAccessClient();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(MAILPIT.getHost());
        sender.setPort(MAILPIT.getMappedPort(1025));
        var delivery = new PasswordSetupDeliveryService(deliveryTransaction,
                new SmtpPasswordSetupMailer(sender, "no-reply@saas.forge.test"),
                URI.create("https://console.saas.forge.test/password-setup"));
        UUID deliveryRequest = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4e06");

        assertEquals(PasswordSetupDeliveryResult.DELIVERED,
                delivery.deliver(KEY_A, deliveryRequest, identityId, null));
        String mail = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025)
                                + "/api/v1/message/latest")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(mail.contains("invited-first-setup@example.test"));
        String challenge = java.util.regex.Pattern.compile("#token=([A-Za-z0-9_-]{43})")
                .matcher(mail).results().findFirst().orElseThrow().group(1);
        assertEquals(1, countWhere("iam_outbox_events", "ordering_key = '" + identityId
                + "' AND event_snapshot::TEXT LIKE '%password-setup-delivered%'"));
        service.establishPassword(KEY_A, challenge, "Invited-Password-2026", null);
        UUID freshRequest = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4e03");
        assertEquals(PasswordSetupDeliveryResult.PASSWORD_READY,
                delivery.deliver(KEY_A, freshRequest, identityId, null));
        assertEquals(1, countWhere("iam_password_setup_challenges", "identity_id = '" + identityId + "'"));
        identities.invalidate(identities.findCredentials(identityId).get(0).id(), NOW);
        UUID historicalRequest = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4e04");
        assertThrows(IdentityCredentialRecoveryRequiredException.class,
                () -> delivery.deliver(KEY_A, historicalRequest, identityId, null));
        assertEquals(1, countWhere("iam_outbox_events", "ordering_key = '" + identityId
                + "' AND event_snapshot::TEXT LIKE '%password-setup-delivered%'"));
    }

    @Test
    void storesOnlyDigestAndAtomicallyReplacesTheOpenChallenge() throws Exception {
        UUID identityId = createIdentity("replace@example.test");
        PasswordSetupChallengeToken first = service.issueChallenge(identityId);
        PasswordSetupChallengeToken second = service.issueChallenge(identityId);

        assertEquals(43, first.value().length());
        assertEquals(43, second.value().length());
        assertFalse(first.value().equals(second.value()));
        byte[] storedDigest = jdbc.queryForObject(
                "SELECT token_digest FROM iam_password_setup_challenges "
                        + "WHERE identity_id = ? AND invalidated_at IS NULL",
                byte[].class, identityId);
        assertArrayEquals(MessageDigest.getInstance("SHA-256")
                .digest(second.value().getBytes(StandardCharsets.UTF_8)), storedDigest);
        assertEquals(1, countWhere("iam_password_setup_challenges",
                "identity_id = '" + identityId + "' AND invalidated_at IS NOT NULL"));
        String rowText = jdbc.queryForObject(
                "SELECT row_to_json(challenge)::TEXT FROM iam_password_setup_challenges challenge "
                        + "WHERE identity_id = ? AND invalidated_at IS NULL", String.class, identityId);
        assertFalse(rowText.contains(first.value()));
        assertFalse(rowText.contains(second.value()));
    }

    @Test
    void atomicallyCreatesCredentialConsumesChallengeStoresReplayAndSafeOutbox() {
        UUID identityId = createIdentity("success@example.test");
        PasswordSetupChallengeToken issued = service.issueChallenge(identityId);
        String password = "Safe-Password-For-Setup-2026";

        service.establishPassword(KEY_A, issued.value(), password, "4bf92f3577b34da6a3ce929d0e0e4736");
        service.establishPassword(KEY_A, issued.value(), "Different-Retry-Password-2026", null);

        assertEquals(1, countWhere("iam_credentials", "identity_id = '" + identityId + "'"));
        assertEquals(1, countWhere("iam_password_setup_challenges",
                "identity_id = '" + identityId + "' AND consumed_at IS NOT NULL"));
        assertEquals(KEY_A, jdbc.queryForObject(
                "SELECT idempotency_key FROM iam_password_setup_challenges WHERE identity_id = ?",
                UUID.class, identityId));
        assertEquals(204, jdbc.queryForObject(
                "SELECT completed_status FROM iam_password_setup_challenges WHERE identity_id = ?",
                Integer.class, identityId));
        String outbox = jdbc.queryForObject(
                "SELECT event_snapshot::TEXT FROM iam_outbox_events WHERE ordering_key = ?",
                String.class, identityId.toString());
        assertTrue(outbox.contains(PasswordEstablishedEventFactory.EVENT_TYPE));
        assertFalse(outbox.contains(password));
        assertFalse(outbox.contains(issued.value()));
        assertEquals(0, countWhere("iam_refresh_token_families", "identity_id = '" + identityId + "'"));
        assertThrows(PasswordSetupTokenInvalidException.class,
                () -> service.establishPassword(KEY_B, issued.value(), password, null));
    }

    @Test
    void concurrentDifferentKeysAllowOneSuccessAndUniformlyRejectTheOther() throws Exception {
        UUID identityId = createIdentity("concurrent@example.test");
        PasswordSetupChallengeToken issued = service.issueChallenge(identityId);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> establish(KEY_A, issued.value()));
            var second = executor.submit(() -> establish(KEY_B, issued.value()));
            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, countWhere("iam_credentials", "identity_id = '" + identityId + "'"));
        assertEquals(1, countWhere("iam_outbox_events", "ordering_key = '" + identityId + "'"));
    }

    @Test
    void rollsBackCredentialChallengeAndOutboxFactWhenOutboxAppendFails() {
        UUID identityId = createIdentity("rollback-setup@example.test");
        PasswordSetupChallengeToken issued = failingService.issueChallenge(identityId);

        assertThrows(IllegalStateException.class,
                () -> failingService.establishPassword(KEY_A, issued.value(), "Rollback-Password-2026", null));

        assertEquals(0, countWhere("iam_credentials", "identity_id = '" + identityId + "'"));
        assertEquals(0, countWhere("iam_password_setup_challenges",
                "identity_id = '" + identityId + "' AND consumed_at IS NOT NULL"));
        assertEquals(0, countWhere("iam_outbox_events", "ordering_key = '" + identityId + "'"));
    }

    private boolean establish(UUID key, String token) {
        try {
            service.establishPassword(key, token, "Concurrent-Password-2026", null);
            return true;
        } catch (PasswordSetupTokenInvalidException exception) {
            return false;
        }
    }

    private UUID createIdentity(String email) {
        return identities.create(Identity.register(email, null, NOW)).id();
    }

    private void ensureTenantAccessClient() {
        if (clients.findById(KEY_A).isEmpty()) {
            clients.createWithId(io.saas.forge.iam.domain.client.OAuthClient.register(
                            "password-setup-test", io.saas.forge.iam.application.bootstrap.ReservedServiceClient.TENANT_ACCESS.allowedScopes(), NOW)
                            .identifiedBy(KEY_A),
                    io.saas.forge.iam.domain.shared.Sha256Digest.of(new byte[32]), NOW);
        }
    }

    private int countWhere(String table, String condition) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + condition, Integer.class);
    }

    private static String iamJdbcUrl() {
        return POSTGRES.getJdbcUrl().replace("/saas.forge", "/iam_db");
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

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @MapperScan(
            basePackages = "io.saas.forge.iam.infrastructure.persistence.mapper",
            sqlSessionFactoryRef = "iamSqlSessionFactory")
    @ComponentScan(basePackageClasses = MyBatisIdentityRepository.class)
    static class TestConfiguration {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(iamJdbcUrl(), "iam_app", "iam-app-password");
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean SqlSessionFactory iamSqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/*Mapper.xml"));
            factory.setTypeHandlersPackage("io.saas.forge.iam.infrastructure.persistence.type");
            return factory.getObject();
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean SecureRandom secureRandom() { return new SecureRandom(); }
        @Bean PasswordSetupChallengeIssuer issuer(SecureRandom random) {
            return new PasswordSetupChallengeIssuer(random);
        }
        @Bean PasswordPolicy passwordPolicy() { return new PasswordPolicy(); }
        @Bean PasswordVerifier passwordVerifier() { return new PasswordVerifier(); }
        @Bean CompromisedPasswordChecker compromisedPasswordChecker() { return password -> false; }
        @Bean PasswordEstablishedEventFactory eventFactory(Clock clock, SecureRandom random) {
            return new PasswordEstablishedEventFactory(
                    new ObjectMapper(), new UuidV7Generator(clock, random), "test");
        }
        @Bean PasswordSetupDeliveredEventFactory deliveredEventFactory(Clock clock, SecureRandom random) {
            return new PasswordSetupDeliveredEventFactory(
                    new ObjectMapper(), new UuidV7Generator(clock, random), "test");
        }
        @Bean PasswordSetupDeliveryTransaction deliveryTransaction(
                io.saas.forge.iam.domain.identity.PasswordSetupDeliveryRepository deliveries,
                IdentityRepository identities, PasswordSetupService setups, OutboxEventRepository outbox,
                PasswordSetupDeliveredEventFactory events, Clock clock) {
            return new PasswordSetupDeliveryTransaction(deliveries, identities, setups, outbox, events, clock);
        }
        @Bean
        @Primary
        PasswordSetupService passwordSetupService(
                PasswordSetupChallengeRepository challenges,
                IdentityRepository identities,
                PasswordSetupChallengeIssuer issuer,
                PasswordPolicy policy,
                CompromisedPasswordChecker compromised,
                PasswordVerifier verifier,
                OutboxEventRepository outbox,
                PasswordEstablishedEventFactory eventFactory,
                Clock clock) {
            return new PasswordSetupService(
                    challenges, identities, issuer, policy, compromised, verifier, outbox, eventFactory, clock);
        }
        @Bean("failingPasswordSetupService")
        PasswordSetupService failingPasswordSetupService(
                PasswordSetupChallengeRepository challenges,
                IdentityRepository identities,
                PasswordSetupChallengeIssuer issuer,
                PasswordPolicy policy,
                CompromisedPasswordChecker compromised,
                PasswordVerifier verifier,
                PasswordEstablishedEventFactory eventFactory,
                Clock clock) {
            OutboxEventRepository failingOutbox = new OutboxEventRepository() {
                @Override public void append(OutboxEvent event) { throw new IllegalStateException("outbox failed"); }
                @Override public Optional<ClaimedOutboxEvent> claimNext(String claimant, Instant at, Instant until) {
                    throw new UnsupportedOperationException();
                }
                @Override public void markPublished(ClaimedOutboxEvent event, Instant at) {
                    throw new UnsupportedOperationException();
                }
                @Override public void releaseAfterFailure(ClaimedOutboxEvent event, Instant at, String summary) {
                    throw new UnsupportedOperationException();
                }
            };
            return new PasswordSetupService(
                    challenges, identities, issuer, policy, compromised, verifier, failingOutbox, eventFactory, clock);
        }
    }
}
