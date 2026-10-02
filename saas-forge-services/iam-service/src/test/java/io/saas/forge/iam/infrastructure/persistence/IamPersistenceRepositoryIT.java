package io.saas.forge.iam.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saas.forge.iam.domain.client.OAuthClient;
import io.saas.forge.iam.domain.client.ClientSecretDigest;
import io.saas.forge.iam.domain.client.OAuthClientManagementOperationRepository;
import io.saas.forge.iam.domain.client.OAuthClientRepository;
import io.saas.forge.iam.domain.client.OAuthClientSecretRotationException;
import io.saas.forge.iam.domain.client.OAuthClientSecretRecoveryException;
import io.saas.forge.iam.domain.client.OAuthClientType;
import io.saas.forge.iam.domain.client.OAuthClientStatus;
import io.saas.forge.iam.domain.client.OAuthScope;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClient;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientBootstrapConflictException;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientBootstrapInput;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientBootstrapResult;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientBootstrapService;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientReplacementException;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientReplacementInput;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientReplacementResult;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientReplacementService;
import io.saas.forge.iam.application.authentication.UuidV7Generator;
import io.saas.forge.iam.application.client.OAuthClientCreatedEventFactory;
import io.saas.forge.iam.domain.client.ReservedServiceClientReplacementRepository;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.application.authentication.IssuedAccessToken;
import io.saas.forge.iam.application.authentication.RefreshRotationTransaction;
import io.saas.forge.iam.application.authentication.RefreshTokenMaterial;
import io.saas.forge.iam.domain.identity.Argon2idPasswordHash;
import io.saas.forge.iam.domain.identity.CredentialType;
import io.saas.forge.iam.domain.identity.DuplicateIdentityEmailException;
import io.saas.forge.iam.domain.identity.Identity;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.identity.PasswordCredential;
import io.saas.forge.iam.domain.session.RefreshTokenConsumption;
import io.saas.forge.iam.domain.session.RefreshTokenFamily;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyContextChange;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saas.forge.iam.domain.session.RefreshRotation;
import io.saas.forge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saas.forge.iam.domain.session.AccessTokenIssuance;
import io.saas.forge.iam.domain.session.RevocationFence;
import io.saas.forge.iam.domain.session.RevocationFenceRepository;
import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import io.saas.forge.iam.domain.session.UserSessionRevocationRepository;
import io.saas.forge.iam.domain.session.UserSessionRevocationStatus;
import io.saas.forge.iam.domain.shared.Sha256Digest;
import io.saas.forge.iam.domain.signing.SigningKey;
import io.saas.forge.iam.domain.signing.SigningKeyRepository;
import io.saas.forge.iam.domain.signing.SigningKeyStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.security.SecureRandom;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Testcontainers
@SpringJUnitConfig(IamPersistenceRepositoryIT.PersistenceConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IamPersistenceRepositoryIT {

    private static final String ARGON2ID_HASH = "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";
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
                    org.testcontainers.utility.MountableFile.forHostPath(REPOSITORY_ROOT.resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    static {
        POSTGRES.start();
    }

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private RefreshTokenFamilyRepository refreshTokenFamilies;

    @Autowired
    private AccessTokenIssuanceRepository accessTokenIssuances;

    @Autowired
    private RevocationFenceRepository revocationFences;

    @Autowired
    private UserSessionRevocationRepository userSessionRevocations;

    @Autowired
    private OAuthClientRepository clients;

    @Autowired
    private OAuthClientManagementOperationRepository clientOperations;

    @Autowired
    private io.saas.forge.iam.infrastructure.persistence.mapper.OAuthClientMapper clientMapper;

    @Autowired
    private ReservedServiceClientBootstrapService reservedClientBootstrap;

    @Autowired
    private ReservedServiceClientReplacementService reservedClientReplacement;

    @Autowired
    private SigningKeyRepository signingKeys;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(iamJdbcUrl(), "iam_migrator", "iam-migrator-password")
                .locations(iamMigrationLocation())
                .load()
                .migrate();
    }

    @Test
    void persistsIdentityAndCredentialInvariantsWithDatabaseGeneratedUuidV7() throws SQLException {
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        Identity identity = identities.create(Identity.register(" Admin@Example.Test ", "管理员", now));

        assertNotNull(identity.id());
        assertEquals(7, uuidVersion(identity.id()));
        assertEquals("admin@example.test", identities.findByEmail(identity.email()).orElseThrow().email().value());
        assertEquals("管理员", identities.findByEmail(identity.email()).orElseThrow().displayName());
        assertThrows(DuplicateIdentityEmailException.class,
                () -> identities.create(Identity.register("admin@example.test", "不会覆盖", now.plusSeconds(1))));

        Identity reused = identities.findOrCreate(Identity.register(" ADMIN@EXAMPLE.TEST ", "不会覆盖", now.plusSeconds(2)));
        assertEquals(identity.id(), reused.id());
        assertEquals("管理员", reused.displayName());

        Identity withoutDisplayName = identities.create(Identity.register("empty-name@example.test", null, now));
        Identity sameDisplayName = identities.create(Identity.register("same-name@example.test", "管理员", now));
        assertNull(withoutDisplayName.displayName());
        assertEquals("管理员", sameDisplayName.displayName());

        PasswordCredential initial = identities.create(PasswordCredential.initial(
                identity.id(), Argon2idPasswordHash.of(ARGON2ID_HASH), now));
        PasswordCredential regular = identities.replaceInitialPassword(initial, PasswordCredential.regular(
                identity.id(), Argon2idPasswordHash.of(ARGON2ID_HASH), now.plusSeconds(2)));

        assertNotNull(initial.id());
        assertNotNull(regular.id());
        assertThrows(IllegalStateException.class, () -> identities.create(PasswordCredential.regular(
                identity.id(), Argon2idPasswordHash.of(ARGON2ID_HASH), now.plusSeconds(3))));
        assertThrows(IllegalStateException.class, () -> identities.replaceInitialPassword(initial, PasswordCredential.regular(
                identity.id(), Argon2idPasswordHash.of(ARGON2ID_HASH), now.plusSeconds(4))));

        var credentials = identities.findCredentials(identity.id());
        assertEquals(2, credentials.size());
        assertEquals(CredentialType.INITIAL_PLATFORM_PASSWORD, credentials.get(0).type());
        assertEquals(now.plusSeconds(2), credentials.get(0).invalidatedAt());
        assertEquals(CredentialType.PASSWORD, credentials.get(1).type());
        assertEquals(regular.id(), credentials.get(1).id());
    }

    @Test
    void atomicallyConsumesRefreshTokensCarriesContextAndRevokesReplayFamily() {
        Instant loginAt = Instant.parse("2026-08-20T01:00:00Z");
        Identity identity = identities.create(Identity.register("session-" + UUID.randomUUID() + "@example.test", null, loginAt));
        Sha256Digest first = digest(1);
        RefreshTokenFamily family = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identity.id(), null, null, loginAt), first, loginAt);

        assertEquals(RefreshTokenConsumption.Status.CONSUMED,
                refreshTokenFamilies.consume(first, loginAt.plusSeconds(1)).status());
        assertEquals(RefreshTokenConsumption.Status.REPLAYED,
                refreshTokenFamilies.consume(first, loginAt.plusSeconds(2)).status());
        assertNotNull(refreshTokenFamilies.findById(family.id()).orElseThrow().revokedAt());

        Sha256Digest presented = digest(2);
        RefreshTokenFamily rotating = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identity.id(), null, null, loginAt), presented, loginAt);
        UUID membershipId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        RefreshTokenConsumption rotated = refreshTokenFamilies.rotate(
                presented, digest(3), membershipId, tenantId, loginAt.plus(1, ChronoUnit.MINUTES));

        assertEquals(RefreshTokenConsumption.Status.CONSUMED, rotated.status());
        assertEquals(membershipId, rotated.family().membershipId());
        assertEquals(tenantId, rotated.family().tenantId());
        assertEquals(loginAt.plus(8, ChronoUnit.HOURS), rotating.absoluteExpiresAt());
        assertEquals(loginAt.plus(8, ChronoUnit.HOURS), rotated.family().absoluteExpiresAt());

        PasswordCredential initial = identities.create(PasswordCredential.initial(
                identity.id(), Argon2idPasswordHash.of(ARGON2ID_HASH), loginAt));
        Sha256Digest restrictedToken = digest(7);
        refreshTokenFamilies.create(RefreshTokenFamily.startInitialPasswordChange(
                identity.id(), initial.id(), loginAt, initial.expiresAt()), restrictedToken, loginAt);
        assertEquals(RefreshTokenConsumption.Status.PURPOSE_MISMATCH,
                refreshTokenFamilies.rotate(
                        restrictedToken, digest(8), null, null, loginAt.plusSeconds(1)).status());
        assertEquals(RefreshTokenConsumption.Status.CONSUMED,
                refreshTokenFamilies.consumeInitialPasswordChange(
                        restrictedToken, loginAt.plusSeconds(2)).status());
    }

    @Test
    void contextChangeCommittedBeforeRefreshLeavesPreparedTokenAndPresentedTokenUnpersisted() throws Exception {
        Instant loginAt = Instant.parse("2026-08-24T01:00:00Z");
        Instant refreshAt = loginAt.plusSeconds(1);
        UUID identityId = identities.create(Identity.register(
                "context-first-" + UUID.randomUUID() + "@example.test", null, loginAt)).id();
        UUID currentMembershipId = UUID.randomUUID();
        UUID currentTenantId = UUID.randomUUID();
        UUID targetMembershipId = UUID.randomUUID();
        UUID targetTenantId = UUID.randomUUID();
        Sha256Digest presentedDigest = digest(41);
        Sha256Digest successorDigest = digest(42);
        RefreshTokenFamily family = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identityId, RefreshTokenFamilyPurpose.USER_TENANT,
                        currentMembershipId, currentTenantId, loginAt),
                presentedDigest, loginAt);
        IssuedAccessToken preparedAccessToken = accessToken(refreshAt);
        RefreshRotationTransaction transaction = refreshRotationTransaction();
        CountDownLatch contextLocked = new CountDownLatch(1);
        CountDownLatch allowContextCommit = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var contextChange = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                RefreshTokenFamilyContextChange result = refreshTokenFamilies.switchTenantContext(
                        family.id(), family.contextVersion(), targetMembershipId, targetTenantId);
                contextLocked.countDown();
                await(allowContextCommit);
                return result;
            }));
            assertTrue(contextLocked.await(5, TimeUnit.SECONDS));
            var refresh = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status ->
                    transaction.commit(
                            new RefreshTokenMaterial("presented", presentedDigest),
                            new RefreshTokenMaterial("successor", successorDigest), digest(43),
                            family.contextVersion(), currentMembershipId, currentTenantId,
                            preparedAccessToken, refreshAt, null)));

            allowContextCommit.countDown();

            assertEquals(RefreshTokenFamilyContextChange.Status.CHANGED,
                    contextChange.get(5, TimeUnit.SECONDS).status());
            assertEquals(RefreshRotation.Status.CONTEXT_CHANGED,
                    refresh.get(5, TimeUnit.SECONDS).status());
        } finally {
            executor.shutdownNow();
        }

        RefreshTokenFamily persisted = refreshTokenFamilies.findById(family.id()).orElseThrow();
        assertEquals(1, persisted.contextVersion());
        assertEquals(targetMembershipId, persisted.membershipId());
        assertEquals(targetTenantId, persisted.tenantId());
        assertEquals(loginAt, persisted.lastUsedAt());
        assertTrue(accessTokenIssuances.findByJti(preparedAccessToken.jti()).isEmpty());
        assertNull(tokenConsumedAt(presentedDigest));
        assertEquals(0, tokenCount(successorDigest));
    }

    @Test
    void refreshHoldingFamilyLockCommitsBeforeContextChangeWithoutOverwritingTheNewContext() throws Exception {
        Instant loginAt = Instant.parse("2026-08-24T02:00:00Z");
        Instant refreshAt = loginAt.plusSeconds(1);
        UUID identityId = identities.create(Identity.register(
                "refresh-first-" + UUID.randomUUID() + "@example.test", null, loginAt)).id();
        UUID currentMembershipId = UUID.randomUUID();
        UUID currentTenantId = UUID.randomUUID();
        UUID targetMembershipId = UUID.randomUUID();
        UUID targetTenantId = UUID.randomUUID();
        Sha256Digest presentedDigest = digest(44);
        Sha256Digest successorDigest = digest(45);
        RefreshTokenFamily family = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identityId, RefreshTokenFamilyPurpose.USER_TENANT,
                        currentMembershipId, currentTenantId, loginAt),
                presentedDigest, loginAt);
        String kid = "context-lock-" + UUID.randomUUID();
        SigningKey published = signingKeys.savePublished(SigningKey.publish(
                kid, "kms/" + kid, "modulus-" + kid, "AQAB",
                loginAt.minus(5, ChronoUnit.MINUTES)));
        signingKeys.activate(published.id(), loginAt);
        IssuedAccessToken accessToken = accessToken(refreshAt, kid);
        RefreshRotationTransaction transaction = refreshRotationTransaction();
        CountDownLatch refreshLocked = new CountDownLatch(1);
        CountDownLatch allowRefreshCommit = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var refresh = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                RefreshRotationTransaction.Result result = transaction.commit(
                        new RefreshTokenMaterial("presented", presentedDigest),
                        new RefreshTokenMaterial("successor", successorDigest), digest(46),
                        family.contextVersion(), currentMembershipId, currentTenantId,
                        accessToken, refreshAt, null);
                refreshLocked.countDown();
                await(allowRefreshCommit);
                return result;
            }));
            boolean locked = refreshLocked.await(5, TimeUnit.SECONDS);
            if (!locked) {
                refresh.get(1, TimeUnit.SECONDS);
            }
            assertTrue(locked);
            var contextChange = executor.submit(() -> refreshTokenFamilies.switchTenantContext(
                    family.id(), family.contextVersion(), targetMembershipId, targetTenantId));

            allowRefreshCommit.countDown();

            assertEquals(RefreshRotation.Status.ROTATED,
                    refresh.get(5, TimeUnit.SECONDS).status());
            assertEquals(RefreshTokenFamilyContextChange.Status.CHANGED,
                    contextChange.get(5, TimeUnit.SECONDS).status());
        } finally {
            executor.shutdownNow();
        }

        RefreshTokenFamily persisted = refreshTokenFamilies.findById(family.id()).orElseThrow();
        assertEquals(1, persisted.contextVersion());
        assertEquals(targetMembershipId, persisted.membershipId());
        assertEquals(targetTenantId, persisted.tenantId());
        assertEquals(refreshAt, persisted.lastUsedAt());
        assertTrue(accessTokenIssuances.findByJti(accessToken.jti()).isPresent());
        assertNotNull(tokenConsumedAt(presentedDigest));
        assertEquals(1, tokenCount(successorDigest));
    }

    @Test
    void readsActorScopedOperationsAndAuthoritativeOverlapWithoutSecretMaterial() {
        Instant at = Instant.parse("2026-09-14T03:00:00Z");
        var actor = identities.create(Identity.register("client-operation-reader@example.test", null, at));
        var creation = clients.create(OAuthClient.register("operation-reader", Set.of(OAuthScope.RUNTIME_READ), at), digest(111), at);
        var ids = new UuidV7Generator(Clock.fixed(at, ZoneOffset.UTC), new SecureRandom());
        var operation = new io.saas.forge.iam.domain.client.OAuthClientManagementOperation(ids.next(), actor.id(), ids.next(),
                "CREATE", creation.client().id(), digest(112), null, creation.initialSecret().id(), "SUCCEEDED", 201, at);
        clientOperations.append(operation);
        var queries = new MyBatisOAuthClientQueries(clientMapper, Clock.fixed(at.plusSeconds(1), ZoneOffset.UTC));
        var page = queries.operations(actor.id(), null, 50);
        assertEquals(1, page.items().size());
        assertEquals(operation.id(), page.items().get(0).operationId());
        assertTrue(page.items().get(0).canRecover());
        assertEquals(at.plusSeconds(600), page.items().get(0).recoveryUntil());
        assertTrue(queries.operations(ids.next(), null, 50).items().isEmpty());
        assertTrue(clientOperations.findById(actor.id(), operation.id()).isPresent());
        assertTrue(clientOperations.findById(ids.next(), operation.id()).isEmpty());
        clients.rotate(creation.client().id(), digest(113), at.plusSeconds(1));
        var status = queries.credentialStatus(creation.client().id());
        assertFalse(status.canRotate());
        assertEquals(at.plusSeconds(86401), status.overlapEndsAt());
        assertFalse(queries.operations(actor.id(), null, 50).items().get(0).canRecover());
    }

    @Test
    void persistsCiAndRemoteDeliveryTypesWithExactDatabaseScopeBoundaries() {
        Instant at = Instant.parse("2026-10-02T12:00:00Z");
        var ci = clients.create(OAuthClient.registerManaged("project CI migration", Set.of(OAuthScope.REMOTE_MANIFEST_REGISTER), at),digest(119),at).client();
        assertEquals(io.saas.forge.iam.domain.client.OAuthClientType.CI_CLIENT,clients.findById(ci.id()).orElseThrow().clientType());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("UPDATE iam_oauth_clients SET allowed_scopes=ARRAY['runtime:read','remote-delivery:manifest:register'] WHERE id=?",ci.id()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("UPDATE iam_oauth_clients SET client_type='RUNTIME_SERVICE' WHERE id=?",ci.id()));
        var remote = clients.create(OAuthClient.register("remote-delivery migration",Set.of(OAuthScope.IAM_PLATFORM_ROLE_READ,OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ,OAuthScope.TENANT_ACCESS_TENANT_READ),at),digest(120),at).client();
        assertEquals(io.saas.forge.iam.domain.client.ReservedServiceKey.REMOTE_DELIVERY,clients.findById(remote.id()).orElseThrow().reservedServiceKey());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("UPDATE iam_oauth_clients SET allowed_scopes=ARRAY['iam:platform-role:read'] WHERE id=?",remote.id()));
    }

    @Test
    void enforcesClientScopeSecretOverlapAndTerminalRevocation() {
        Instant now = Instant.parse("2026-08-20T02:00:00Z");
        Instant rotatedAt = now.plusSeconds(1);
        Sha256Digest initialSecret = digest(4);
        Sha256Digest rotatedSecret = digest(5);
        Sha256Digest secondRotatedSecret = digest(6);
        OAuthClient client = clients.create(
                OAuthClient.register("worker", Set.of(OAuthScope.RUNTIME_READ), now), initialSecret, now).client();

        assertTrue(clients.findActiveBySecretDigest(initialSecret, now).isPresent());
        clients.rotate(client.id(), rotatedSecret, rotatedAt);
        OAuthClientSecretRotationException overlap = assertThrows(OAuthClientSecretRotationException.class,
                () -> clients.rotate(client.id(), secondRotatedSecret, rotatedAt.plusSeconds(1)));
        assertEquals(OAuthClientSecretRotationException.Reason.OVERLAP_ACTIVE, overlap.reason());
        assertTrue(clients.findActiveBySecretDigest(initialSecret,
                rotatedAt.plus(24, ChronoUnit.HOURS).minus(1, ChronoUnit.MICROS)).isPresent());
        assertFalse(clients.findActiveBySecretDigest(initialSecret,
                rotatedAt.plus(24, ChronoUnit.HOURS)).isPresent());
        assertTrue(clients.findActiveBySecretDigest(rotatedSecret, rotatedAt).isPresent());

        Instant secondRotationAt = rotatedAt.plus(24, ChronoUnit.HOURS);
        clients.rotate(client.id(), secondRotatedSecret, secondRotationAt);
        assertTrue(clients.findActiveBySecretDigest(rotatedSecret, secondRotationAt).isPresent());
        assertTrue(clients.findActiveBySecretDigest(secondRotatedSecret, secondRotationAt).isPresent());
        assertEquals(secondRotationAt, clients.findById(client.id()).orElseThrow().updatedAt());

        clients.revoke(client.id(), secondRotationAt.plusSeconds(1));
        assertFalse(clients.findActiveBySecretDigest(secondRotatedSecret,
                secondRotationAt.plusSeconds(2)).isPresent());
    }

    @Test
    void concurrentClientSecretRotationsKeepOnlyOneOverlapWindow() throws Exception {
        Instant now = Instant.parse("2026-08-20T03:00:00Z");
        OAuthClient client = clients.create(
                OAuthClient.register("concurrent-worker", Set.of(OAuthScope.RUNTIME_READ), now), digest(31), now).client();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> rotateConcurrently(client.id(), digest(32), now.plusSeconds(1), ready, start));
            var second = executor.submit(() -> rotateConcurrently(client.id(), digest(33), now.plusSeconds(1), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(List.of("OVERLAP_ACTIVE", "SUCCEEDED"),
                    java.util.stream.Stream.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
                            .sorted().toList());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recoveryRevokesOnlyTheLostSecretAndPreservesTheStableOverlapDeadline() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-20T03:30:00Z");
        Instant rotatedAt = createdAt.plusSeconds(1);
        Instant recoveredAt = rotatedAt.plusSeconds(30);
        Sha256Digest stableDigest = digest(34);
        Sha256Digest lostDigest = digest(35);
        Sha256Digest replacementDigest = digest(36);
        var creation = clients.create(
                OAuthClient.register("recovery-worker", Set.of(OAuthScope.RUNTIME_READ), createdAt),
                stableDigest, createdAt);
        var lostSecret = clients.rotate(creation.client().id(), lostDigest, rotatedAt);
        Instant stableDeadline = secretValidUntil(creation.initialSecret().id());

        var replacement = clients.recover(
                creation.client().id(), lostSecret.id(), replacementDigest, recoveredAt);

        assertEquals(rotatedAt.plus(24, ChronoUnit.HOURS), stableDeadline);
        assertEquals(stableDeadline, secretValidUntil(creation.initialSecret().id()));
        assertTrue(clients.findActiveBySecretDigest(stableDigest, recoveredAt).isPresent());
        assertFalse(clients.findActiveBySecretDigest(lostDigest, recoveredAt).isPresent());
        assertTrue(clients.findActiveBySecretDigest(replacementDigest, recoveredAt).isPresent());
        assertEquals(recoveredAt, clients.findById(creation.client().id()).orElseThrow().updatedAt());
        assertEquals(7, replacement.id().version());

        OAuthClientSecretRecoveryException second = assertThrows(
                OAuthClientSecretRecoveryException.class,
                () -> clients.recover(creation.client().id(), lostSecret.id(), digest(37), recoveredAt.plusSeconds(1)));
        assertEquals(OAuthClientSecretRecoveryException.Reason.SECRET_NOT_RECOVERABLE, second.reason());
    }

    @Test
    void concurrentRecoveriesForTheSameOriginalOperationAreSerialized() throws Exception {
        UUID originalOperationId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                assertTrue(clientOperations.tryLockRecovery(originalOperationId));
                locked.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待并发恢复验证被中断", exception);
                }
                return true;
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));

            var second = executor.submit(() -> new TransactionTemplate(transactionManager).execute(
                    status -> clientOperations.tryLockRecovery(originalOperationId)));
            assertFalse(second.get(5, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));

            assertEquals(Boolean.TRUE, new TransactionTemplate(transactionManager).execute(
                    status -> clientOperations.tryLockRecovery(originalOperationId)));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void persistsFixedRuntimeClientIdAndSecretDigestOnly() throws SQLException {
        Instant now = Instant.parse("2026-08-21T02:00:00Z");
        UUID clientId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        Sha256Digest digest = ClientSecretDigest.fromPlaintext(secret);
        OAuthClient client = OAuthClient.register(
                        "runtime-worker", Set.of(OAuthScope.RUNTIME_READ), now)
                .identifiedBy(clientId);

        OAuthClient persisted = clients.createWithId(client, digest, now);
        var state = clients.findBootstrapState(clientId).orElseThrow();

        assertEquals(clientId, persisted.id());
        assertEquals(OAuthClientType.RUNTIME_SERVICE, persisted.clientType());
        assertNull(persisted.reservedServiceKey());
        assertEquals(now, persisted.updatedAt());
        assertEquals(Set.of(OAuthScope.RUNTIME_READ), state.client().allowedScopes());
        assertTrue(state.hasCurrentSecret(digest, now));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT secret_digest::text FROM iam_oauth_client_secrets WHERE client_id = ?")) {
            statement.setObject(1, clientId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertFalse(result.getString(1).contains(secret));
            }
        }
    }

    @Test
    void reservedClientBootstrapIsStrictlyIdempotentAndRejectsDatabaseDrift() {
        List<ReservedServiceClientBootstrapInput> inputs = List.of(
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.IAM,
                        UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c92"), serviceClientSecret((byte) 21)),
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.TENANT_ACCESS,
                        UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c93"), serviceClientSecret((byte) 22)),
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.ENTITLEMENT,
                        UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c94"), serviceClientSecret((byte) 23)));

        ReservedServiceClientBootstrapResult initialized = reservedClientBootstrap.bootstrap(inputs);
        ReservedServiceClientBootstrapResult replayed = reservedClientBootstrap.bootstrap(inputs);

        assertTrue(initialized.clients().values().stream()
                .allMatch(value -> value.outcome() == ReservedServiceClientBootstrapResult.Outcome.INITIALIZED));
        assertTrue(replayed.clients().values().stream()
                .allMatch(value -> value.outcome() == ReservedServiceClientBootstrapResult.Outcome.ALREADY_INITIALIZED));
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                "UPDATE iam_oauth_clients SET display_name = 'drifted-service' WHERE id = ?",
                inputs.get(0).clientId());
        assertThrows(ReservedServiceClientBootstrapConflictException.class,
                () -> reservedClientBootstrap.bootstrap(inputs));
        deleteReservedClientTestData(inputs.stream().map(ReservedServiceClientBootstrapInput::clientId).toList());
    }

    @Test
    void reservedClientBootstrapAndReplacementUseCurrentSecretsAndStrictDeploymentIdempotency()
            throws Exception {
        Instant bootstrapAt = Instant.parse("2026-08-21T08:00:00Z");
        UUID oldClientId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4ca2");
        UUID newClientId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4ca3");
        UUID requestId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4ca4");
        String oldSecret = serviceClientSecret((byte) 31);
        String rotatedSecret = serviceClientSecret((byte) 32);
        List<ReservedServiceClientBootstrapInput> initial = List.of(
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.IAM, oldClientId, oldSecret),
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.TENANT_ACCESS,
                        UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4ca5"),
                        serviceClientSecret((byte) 33)),
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.ENTITLEMENT,
                        UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4ca6"),
                        serviceClientSecret((byte) 34)));
        reservedClientBootstrap.bootstrap(initial);
        clients.rotate(oldClientId, ClientSecretDigest.fromPlaintext(rotatedSecret), bootstrapAt.plusSeconds(1));

        List<ReservedServiceClientBootstrapInput> rotatedMount = List.of(
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.IAM, oldClientId, rotatedSecret),
                initial.get(1), initial.get(2));
        ReservedServiceClientBootstrapResult rerun = reservedClientBootstrap.bootstrap(rotatedMount);
        assertEquals(ReservedServiceClientBootstrapResult.Outcome.ALREADY_INITIALIZED,
                rerun.clients().get(ReservedServiceClient.IAM).outcome());

        new JdbcTemplate(dataSource).update(
                "UPDATE iam_oauth_client_secrets SET valid_until = ? WHERE client_id = ? AND secret_digest = ?",
                OffsetDateTime.ofInstant(bootstrapAt, ZoneOffset.UTC), oldClientId,
                ClientSecretDigest.fromPlaintext(oldSecret).value());
        ReservedServiceClientBootstrapConflictException expired = assertThrows(
                ReservedServiceClientBootstrapConflictException.class,
                () -> reservedClientBootstrap.bootstrap(initial));
        assertEquals(ReservedServiceClientBootstrapConflictException.Reason.MOUNTED_SECRET_NOT_CURRENT,
                expired.reason());

        clients.revoke(oldClientId, bootstrapAt.plusSeconds(2));
        ReservedServiceClientBootstrapConflictException revoked = assertThrows(
                ReservedServiceClientBootstrapConflictException.class,
                () -> reservedClientBootstrap.bootstrap(rotatedMount));
        assertEquals(ReservedServiceClientBootstrapConflictException.Reason.CLIENT_REVOKED, revoked.reason());

        ReservedServiceClientReplacementInput replacement = new ReservedServiceClientReplacementInput(
                requestId, ReservedServiceClient.IAM, oldClientId, newClientId,
                serviceClientSecret((byte) 35));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> reservedClientReplacement.replace(replacement, null));
            var second = executor.submit(() -> reservedClientReplacement.replace(replacement, null));
            Set<ReservedServiceClientReplacementResult.Outcome> outcomes = Set.of(
                    first.get(10, TimeUnit.SECONDS).outcome(), second.get(10, TimeUnit.SECONDS).outcome());
            assertEquals(Set.of(
                    ReservedServiceClientReplacementResult.Outcome.REPLACED,
                    ReservedServiceClientReplacementResult.Outcome.ALREADY_REPLACED), outcomes);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(OAuthClientStatus.REVOKED, clients.findById(oldClientId).orElseThrow().status());
        OAuthClient replacementClient = clients.findById(newClientId).orElseThrow();
        assertEquals(ReservedServiceClient.IAM.serviceKey(), replacementClient.reservedServiceKey());
        assertEquals(ReservedServiceClient.IAM.allowedScopes(), replacementClient.allowedScopes());
        assertEquals(1, new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM iam_oauth_clients WHERE reserved_service_key = 'IAM' AND client_status = 'ACTIVE'",
                Integer.class));
        String event = new JdbcTemplate(dataSource).queryForObject(
                "SELECT event_snapshot::text FROM iam_outbox_events WHERE ordering_key = ?",
                String.class, newClientId.toString());
        assertTrue(event.contains("\"actorType\": \"DEPLOYMENT\""));
        assertTrue(event.contains("\"deploymentOperationId\": \"" + requestId + "\""));
        assertFalse(event.contains(replacement.newClientSecret()));
        assertFalse(event.toLowerCase().contains("digest"));

        ReservedServiceClientReplacementException conflict = assertThrows(
                ReservedServiceClientReplacementException.class,
                () -> reservedClientReplacement.replace(new ReservedServiceClientReplacementInput(
                        requestId, ReservedServiceClient.IAM, oldClientId, newClientId,
                        serviceClientSecret((byte) 36)), null));
        assertEquals(ReservedServiceClientReplacementException.Reason.REQUEST_CONFLICT, conflict.reason());

        deleteReservedClientTestData(List.of(
                oldClientId, newClientId, initial.get(1).clientId(), initial.get(2).clientId()));
    }

    @Test
    void databaseRejectsRuntimeInternalScopesAndReservedScopeMismatches() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO iam_oauth_clients
                    (display_name, client_type, reserved_service_key, allowed_scopes,
                     client_status, created_at, updated_at)
                VALUES ('invalid-runtime', 'RUNTIME_SERVICE', NULL, ARRAY['iam:identity:write'],
                        'ACTIVE', now(), now())
                """));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO iam_oauth_clients
                    (display_name, client_type, reserved_service_key, allowed_scopes,
                     client_status, created_at, updated_at)
                VALUES ('invalid-reserved', 'RESERVED_SERVICE', 'IAM', ARRAY['runtime:read'],
                        'ACTIVE', now(), now())
                """));
    }

    private String rotateConcurrently(
            UUID clientId,
            Sha256Digest digest,
            Instant at,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            clients.rotate(clientId, digest, at);
            return "SUCCEEDED";
        } catch (OAuthClientSecretRotationException exception) {
            assertEquals(OAuthClientSecretRotationException.Reason.OVERLAP_ACTIVE, exception.reason());
            return "OVERLAP_ACTIVE";
        }
    }

    private Instant secretValidUntil(UUID secretId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT valid_until FROM iam_oauth_client_secrets WHERE id = ?")) {
            statement.setObject(1, secretId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getTimestamp(1).toInstant();
            }
        }
    }

    @Test
    void migrationFailsClosedForUnknownHistoricScopeCombination() {
        String schema = "oauth_backfill_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(iamJdbcUrl(), "saas.forge_admin", "admin-password")
                .schemas(schema)
                .locations(iamMigrationLocation())
                .target("20")
                .load()
                .migrate();
        JdbcTemplate legacy = new JdbcTemplate(new DriverManagerDataSource(
                iamJdbcUrl(), "saas.forge_admin", "admin-password"));
        legacy.update("""
                INSERT INTO %s.iam_oauth_clients
                    (display_name, allowed_scopes, client_status, created_at)
                VALUES ('unknown-historic-client', ARRAY['iam:identity:write'], 'ACTIVE', now())
                """.formatted(schema));

        Flyway migration = Flyway.configure()
                .dataSource(iamJdbcUrl(), "saas.forge_admin", "admin-password")
                .schemas(schema)
                .locations(iamMigrationLocation())
                .load();
        assertThrows(FlywayException.class, migration::migrate);
    }

    @Test
    void persistsSigningKeyMetadataEnforcesUniquenessAndLifecycle() throws SQLException {
        Instant now = Instant.parse("2026-08-20T03:00:00Z");
        SigningKey first = signingKeys.savePublished(SigningKey.publish("kid-" + UUID.randomUUID(), "kms/key/1", "modulus-1", "AQAB", now));
        assertTrue(signingKeys.findPublishedVerificationKeys().stream().anyMatch(key -> key.id().equals(first.id())));

        assertThrows(IllegalStateException.class, () -> signingKeys.activate(first.id(), now.plus(4, ChronoUnit.MINUTES)));
        signingKeys.activate(first.id(), now.plus(5, ChronoUnit.MINUTES));
        assertEquals(Duration.ofHours(8),
                signingKeys.prepareActiveForIssuance(Duration.ofHours(8)).maxIssuedTokenTtl());
        assertEquals(Duration.ofHours(8),
                signingKeys.prepareActiveForIssuance(Duration.ofMinutes(15)).maxIssuedTokenTtl());
        SigningKey persistedFirst = signingKeys.findActive().orElseThrow();
        assertEquals(first.kid(), persistedFirst.kid());
        assertEquals("kms/key/1", persistedFirst.keyVersionReference());
        assertEquals("modulus-1", persistedFirst.publicJwkModulus());
        assertEquals("AQAB", persistedFirst.publicJwkExponent());

        assertThrows(DataIntegrityViolationException.class, () -> signingKeys.savePublished(SigningKey.publish(
                first.kid(), "kms/key/duplicate", "modulus-duplicate", "AQAB", now.plus(6, ChronoUnit.MINUTES))));
        assertDatabaseRejectsSecondActiveKey(now.plus(6, ChronoUnit.MINUTES));

        SigningKey second = signingKeys.savePublished(SigningKey.publish(
                "kid-" + UUID.randomUUID(), "kms/key/2", "modulus-2", "AQAB", now.plus(6, ChronoUnit.MINUTES)));
        SigningKey active = signingKeys.activate(second.id(), now.plus(11, ChronoUnit.MINUTES));
        assertEquals(SigningKeyStatus.ACTIVE, active.status());
        assertEquals(second.id(), signingKeys.findActive().orElseThrow().id());

        SigningKey replacement = signingKeys.savePublished(SigningKey.publish(
                "kid-" + UUID.randomUUID(), "kms/key/3", "modulus-3", "AQAB", now.plus(6, ChronoUnit.MINUTES)));
        SigningKey revoked = signingKeys.revoke(second.id(), replacement.id(), now.plus(12, ChronoUnit.MINUTES));
        assertEquals(SigningKeyStatus.REVOKED, revoked.status());
        assertEquals(replacement.id(), signingKeys.findActive().orElseThrow().id());
        assertFalse(signingKeys.findPublishedVerificationKeys().stream().anyMatch(key -> key.id().equals(second.id())));

        assertThrows(IllegalStateException.class, () -> signingKeys.retire(first.id(), now.plus(491, ChronoUnit.MINUTES)));
        assertEquals(SigningKeyStatus.RETIRED, signingKeys.retire(first.id(), now.plus(492, ChronoUnit.MINUTES)).status());
        assertFalse(signingKeys.findPublishedVerificationKeys().stream().anyMatch(key -> key.id().equals(first.id())));
    }

    @Test
    void migrationGrantsOnlyRuntimeDmlAndDoesNotCreatePrivateKeyColumns() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertFalse(columnExists(connection, "iam_signing_keys", "private_key"));
            assertFalse(columnExists(connection, "iam_signing_keys", "private_jwk"));
            assertThrows(SQLException.class, () -> connection.createStatement().execute("CREATE TABLE iam_probe (id UUID)"));
            assertThrows(SQLException.class, () -> connection.createStatement().execute(
                    "INSERT INTO iam_oauth_clients (display_name, allowed_scopes, client_status, created_at) "
                            + "VALUES ('invalid', ARRAY['tenant:write'], 'ACTIVE', now())"));
        }
    }

    @Test
    void targetRevocationUsesStableBatchesAndPreservesSwitchedFamilyAndOtherPurposes() {
        Instant now = Instant.parse("2026-08-25T08:00:00Z");
        UUID targetTenant = uuidV7();
        UUID targetMembership = uuidV7();
        UUID otherTenant = uuidV7();
        UUID otherMembership = uuidV7();
        UUID identityId = identities.create(Identity.register(
                "batch-" + UUID.randomUUID() + "@example.test", null, now)).id();
        RefreshTokenFamily current = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identityId, RefreshTokenFamilyPurpose.USER_TENANT,
                        targetMembership, targetTenant, now), digest(71), now);
        RefreshTokenFamily switched = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identityId, RefreshTokenFamilyPurpose.USER_TENANT,
                        otherMembership, otherTenant, now), digest(72), now);
        RefreshTokenFamily platform = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identityId, RefreshTokenFamilyPurpose.USER_PLATFORM,
                        null, null, now), digest(73), now);

        String kid = "batch-revocation-" + UUID.randomUUID();
        SigningKey published = signingKeys.savePublished(SigningKey.publish(
                kid, "kms/" + kid, "modulus-" + kid, "AQAB", now.minusSeconds(300)));
        signingKeys.activate(published.id(), now);
        UUID currentJti = uuidV7();
        UUID historicalTargetJti = uuidV7();
        UUID switchedCurrentJti = uuidV7();
        UUID platformJti = uuidV7();
        accessTokenIssuances.create(new AccessTokenIssuance(currentJti, current.id(), identityId,
                otherMembership, otherTenant, kid, now, now.plusSeconds(900)));
        accessTokenIssuances.create(new AccessTokenIssuance(historicalTargetJti, switched.id(), identityId,
                targetMembership, targetTenant, kid, now, now.plusSeconds(900)));
        accessTokenIssuances.create(new AccessTokenIssuance(switchedCurrentJti, switched.id(), identityId,
                otherMembership, otherTenant, kid, now, now.plusSeconds(900)));
        accessTokenIssuances.create(new AccessTokenIssuance(platformJti, platform.id(), identityId,
                null, null, kid, now, now.plusSeconds(900)));

        UUID requestId = uuidV7();
        RevocationFenceTarget target = RevocationFenceTarget.membership(targetMembership, targetTenant);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            revocationFences.lock(target);
            revocationFences.create(RevocationFence.establish(requestId, target, now));
        });
        var workflow = userSessionRevocations.create(requestId, target, now);

        for (int batch = 0; batch < 3; batch++) {
            workflow = userSessionRevocations.claim(requestId, "test-worker", now.plusSeconds(batch),
                    now.plusSeconds(batch + 30), 10).orElseThrow();
            var candidates = userSessionRevocations.loadBatch(workflow, 1, now.plusSeconds(batch));
            workflow = userSessionRevocations.commitBatch(workflow, candidates, now.plusSeconds(batch));
        }

        assertEquals(UserSessionRevocationStatus.COMPLETED, workflow.status());
        assertEquals(1, workflow.revokedFamilyCount());
        assertEquals(2, workflow.revokedJtiCount());
        assertNotNull(refreshTokenFamilies.findById(current.id()).orElseThrow().revokedAt());
        assertNull(refreshTokenFamilies.findById(switched.id()).orElseThrow().revokedAt());
        assertNull(refreshTokenFamilies.findById(platform.id()).orElseThrow().revokedAt());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", OffsetDateTime.class, currentJti));
        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", OffsetDateTime.class,
                historicalTargetJti));
        assertNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", OffsetDateTime.class,
                switchedCurrentJti));
        assertNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", OffsetDateTime.class, platformJti));
    }

    @Test
    void revocationLeaseTakeoverFencesStaleWorkerAndExhaustionCanBeExplicitlyRecovered() {
        Instant now = Instant.parse("2026-08-25T09:00:00Z");
        RevocationFenceTarget target = RevocationFenceTarget.tenant(uuidV7());
        UUID requestId = uuidV7();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            revocationFences.lock(target);
            revocationFences.create(RevocationFence.establish(requestId, target, now));
        });
        userSessionRevocations.create(requestId, target, now);
        var stale = userSessionRevocations.claim(
                requestId, "worker-a", now, now.plusSeconds(1), 10).orElseThrow();
        var takeover = userSessionRevocations.claim(
                requestId, "worker-b", now.plusSeconds(2), now.plusSeconds(32), 10).orElseThrow();
        var emptyBatch = userSessionRevocations.loadBatch(takeover, 1, now.plusSeconds(2));

        assertTrue(takeover.fencingToken() > stale.fencingToken());
        assertThrows(IllegalStateException.class,
                () -> userSessionRevocations.commitBatch(stale, emptyBatch, now.plusSeconds(2)));
        assertEquals(UserSessionRevocationStatus.COMPLETED,
                userSessionRevocations.commitBatch(takeover, emptyBatch, now.plusSeconds(2)).status());

        RevocationFenceTarget recoveryTarget = RevocationFenceTarget.tenant(uuidV7());
        UUID recoveryRequest = uuidV7();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            revocationFences.lock(recoveryTarget);
            revocationFences.create(RevocationFence.establish(recoveryRequest, recoveryTarget, now));
        });
        userSessionRevocations.create(recoveryRequest, recoveryTarget, now);
        var exhausted = userSessionRevocations.claim(
                recoveryRequest, "worker-c", now, now.plusSeconds(30), 1).orElseThrow();
        userSessionRevocations.exhaust(exhausted, now.plusSeconds(1), "REVOCATION_INDEX_UNAVAILABLE");
        assertEquals(UserSessionRevocationStatus.RECOVERY_REQUIRED,
                userSessionRevocations.find(recoveryRequest).orElseThrow().status());
        userSessionRevocations.recover(recoveryRequest, now.plusSeconds(2));
        var recovered = userSessionRevocations.find(recoveryRequest).orElseThrow();
        assertEquals(UserSessionRevocationStatus.PENDING, recovered.status());
        assertEquals(0, recovered.attemptCount());
        assertNull(recovered.lastFailure());
    }

    private RefreshRotationTransaction refreshRotationTransaction() {
        return new RefreshRotationTransaction(
                refreshTokenFamilies,
                org.mockito.Mockito.mock(io.saas.forge.iam.domain.session.TenantContextSwitchRepository.class),
                accessTokenIssuances, null, null, null, null, Duration.ofSeconds(10),
                (membershipId, tenantId) -> { });
    }

    private static IssuedAccessToken accessToken(Instant issuedAt) {
        return accessToken(issuedAt, "test-kid");
    }

    private static IssuedAccessToken accessToken(Instant issuedAt, String kid) {
        return new IssuedAccessToken(
                "prepared-access-token", uuidV7(), kid,
                issuedAt, issuedAt.plusSeconds(900), 900);
    }

    private static UUID uuidV7() {
        long random = UUID.randomUUID().getLeastSignificantBits();
        return new UUID(0x0198c9d50f257000L, (random & 0x3fffffffffffffffL) | 0x8000000000000000L);
    }

    private OffsetDateTime tokenConsumedAt(Sha256Digest digest) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT consumed_at FROM iam_refresh_tokens WHERE token_digest = ?",
                OffsetDateTime.class, digest.value());
    }

    private int tokenCount(Sha256Digest digest) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM iam_refresh_tokens WHERE token_digest = ?",
                Integer.class, digest.value());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发事务超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发事务被中断", exception);
        }
    }

    private int uuidVersion(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT uuid_extract_version(?)")) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static Sha256Digest digest(int value) {
        byte[] digest = new byte[32];
        digest[0] = (byte) value;
        return Sha256Digest.of(digest);
    }

    private static String serviceClientSecret(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?)")) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private void assertDatabaseRejectsSecondActiveKey(Instant at) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO iam_signing_keys "
                                + "(kid, key_version_reference, public_jwk_modulus, public_jwk_exponent, key_status, published_at, activated_at) "
                                + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)")) {
            OffsetDateTime timestamp = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
            statement.setString(1, "direct-active-" + UUID.randomUUID());
            statement.setString(2, "kms/key/direct");
            statement.setString(3, "modulus-direct");
            statement.setString(4, "AQAB");
            statement.setObject(5, timestamp);
            statement.setObject(6, timestamp);

            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private static String iamJdbcUrl() {
        return POSTGRES.getJdbcUrl().replace("/saas.forge", "/iam_db");
    }

    private static void deleteReservedClientTestData(List<UUID> clientIds) {
        JdbcTemplate migrator = new JdbcTemplate(
                new DriverManagerDataSource(iamJdbcUrl(), "iam_migrator", "iam-migrator-password"));
        for (UUID clientId : clientIds) {
            migrator.update(
                    "DELETE FROM iam_outbox_events WHERE ordering_key = ?", clientId.toString());
            migrator.update(
                    "DELETE FROM iam_reserved_service_client_replacements WHERE old_client_id = ? OR new_client_id = ?",
                    clientId, clientId);
        }
        for (UUID clientId : clientIds) {
            migrator.update("DELETE FROM iam_oauth_client_secrets WHERE client_id = ?", clientId);
            migrator.update("DELETE FROM iam_oauth_clients WHERE id = ?", clientId);
        }
    }

    private static String iamMigrationLocation() {
        return "filesystem:" + REPOSITORY_ROOT
                .resolve("saas-forge-services/iam-service/src/main/resources/db/migration");
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

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @MapperScan(basePackages = "io.saas.forge.iam.infrastructure.persistence.mapper", sqlSessionFactoryRef = "iamSqlSessionFactory")
    @ComponentScan(basePackageClasses = MyBatisIdentityRepository.class)
    static class PersistenceConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC);
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
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*Mapper.xml"));
            factory.setTypeHandlersPackage("io.saas.forge.iam.infrastructure.persistence.type");
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory iamSqlSessionFactory) {
            return new SqlSessionTemplate(iamSqlSessionFactory);
        }

        @Bean
        ReservedServiceClientBootstrapService reservedServiceClientBootstrapService(OAuthClientRepository clients) {
            return new ReservedServiceClientBootstrapService(
                    clients, Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC));
        }

        @Bean
        ReservedServiceClientReplacementService reservedServiceClientReplacementService(
                OAuthClientRepository clients,
                ReservedServiceClientReplacementRepository replacements,
                OutboxEventRepository outbox) {
            Clock clock = Clock.fixed(Instant.parse("2026-08-21T08:00:03Z"), ZoneOffset.UTC);
            return new ReservedServiceClientReplacementService(
                    clients,
                    replacements,
                    outbox,
                    new OAuthClientCreatedEventFactory(
                            new tools.jackson.databind.ObjectMapper(),
                            new UuidV7Generator(clock, new SecureRandom()),
                            "test"),
                    clock);
        }
    }
}
