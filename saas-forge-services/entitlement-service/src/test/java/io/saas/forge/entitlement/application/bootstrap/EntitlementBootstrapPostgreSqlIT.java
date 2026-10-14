package io.saas.forge.entitlement.application.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandRequest;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaPurpose;
import io.saas.forge.entitlement.application.quota.QuotaCommandApplicationService;
import io.saas.forge.entitlement.application.quota.QuotaCommandException;
import io.saas.forge.entitlement.application.quota.QuotaOperationIdReusedException;
import io.saas.forge.entitlement.domain.plan.PlanTransitionException;
import io.saas.forge.entitlement.domain.quota.QuotaOperationOutcome;
import io.saas.forge.entitlement.domain.quota.QuotaOperationPurpose;
import io.saas.forge.entitlement.domain.quota.QuotaDefinitionTransitionException;
import io.saas.forge.entitlement.infrastructure.persistence.MyBatisEntitlementBootstrapIdempotency;
import io.saas.forge.entitlement.infrastructure.persistence.MyBatisEntitlementOutboxEventRepository;
import io.saas.forge.entitlement.infrastructure.persistence.MyBatisPlanRepository;
import io.saas.forge.entitlement.infrastructure.persistence.MyBatisQuotaDefinitionRepository;
import io.saas.forge.entitlement.infrastructure.persistence.MyBatisQuotaOperationRepository;
import io.saas.forge.entitlement.infrastructure.persistence.MyBatisSubscriptionRepository;
import io.saas.forge.entitlement.infrastructure.grpc.QuotaCommandGrpcService;
import io.saas.forge.entitlement.infrastructure.grpc.QuotaCommandServerInterceptor;
import io.saas.forge.sdk.auth.ServiceAccessAuthorization;
import io.saas.forge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saas.forge.sdk.auth.ServiceAccessTokenScopeException;
import io.saas.forge.entitlement.application.subscription.CreateInitialSubscriptionService;
import io.saas.forge.entitlement.application.subscription.TenantEligibilityGateway;
import io.saas.forge.entitlement.domain.subscription.InitialSubscriptionAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringJUnitConfig(EntitlementBootstrapPostgreSqlIT.PersistenceConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntitlementBootstrapPostgreSqlIT {
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
                    MountableFile.forHostPath(REPOSITORY_ROOT.resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    static {
        POSTGRES.start();
    }

    @Autowired
    private io.saas.forge.entitlement.application.subscription.SubscriptionRecoveryRepository subscriptionRecoveryRepository;

    @Autowired
    private io.saas.forge.entitlement.application.subscription.RecoverableSubscriptionService recoverableSubscriptions;

    @Autowired
    private io.saas.forge.entitlement.application.subscription.SubscriptionQueries subscriptionQueries;

    @Autowired
    private EntitlementBootstrapService service;

    @Autowired
    private CreateInitialSubscriptionService initialSubscriptions;

    @Autowired
    private QuotaCommandApplicationService quotaCommands;

    @BeforeAll
    void migrate() throws Exception {
        Flyway.configure()
                .dataSource(jdbcUrl(), "entitlement_migrator", "entitlement-migrator-password")
                .locations("classpath:db/migration").target("5").load().migrate();
        // 模拟升级前稳定零额度结果，在应用 V6 前保存；升级后仍按原请求重放。
        UUID actor = uuidV7(970), key = uuidV7(971), definition = uuidV7(972), plan = uuidV7(973);
        var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var legacy = new PlanResult(plan, "legacy-zero", "Legacy Zero", io.saas.forge.entitlement.domain.plan.PlanStatus.DRAFT,
                java.util.List.of(new io.saas.forge.entitlement.domain.plan.PlanQuotaLimit(definition, 0)), now, now);
        String body = new ObjectMapper().writeValueAsString(legacy);
        executeAsMigrator("INSERT INTO quota_definitions VALUES ('" + definition + "', 'max_users', 'ACTIVE', now(), now())");
        executeAsMigrator("INSERT INTO plans VALUES ('" + plan + "', 'legacy-zero', 'Legacy Zero', 'DRAFT', now(), now())");
        executeAsMigrator("INSERT INTO plan_quotas VALUES ('" + plan + "', '" + definition + "', 0)");
        executeAsMigrator("INSERT INTO entitlement_bootstrap_idempotency VALUES ('" + actor + "', '" + key
                + "', 'CREATE_PLAN', repeat('a', 64), '" + plan + "', 201, 'PLAN', '" + body + "', now(), now() + interval '24 hours')");
        Flyway.configure().dataSource(jdbcUrl(), "entitlement_migrator", "entitlement-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var operation = recoverablePlans.list(actor, null, 50).items().get(0);
        assertEquals(legacy, operation.result());
        assertEquals(legacy, recoverablePlans.create(actor, key, new PlanDraft("legacy-zero", "Legacy Zero", definition, 0), null));
        assertEquals(0, planQueries.get(plan).quotaLimits().get(0).limit());
        assertEquals("DRAFT", planQueries.get(plan).status().name());
        assertEquals(0, count("entitlement_outbox_events"));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new io.saas.forge.entitlement.api.EntitlementBootstrapController(authorization -> actor,
                        recoverablePlans, planQueries, recoverableSubscriptions, recoverableQuota, quotaQueries, subscriptionQueries))
                .setControllerAdvice(new io.saas.forge.entitlement.api.EntitlementBootstrapExceptionHandler()).build();
        String legacyRequest = "{\"code\":\"legacy-zero\",\"displayName\":\"Legacy Zero\",\"quotaLimits\":[{\"quotaDefinitionId\":\"" + definition + "\",\"limit\":0}]}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/platform/plans")
                .header("Idempotency-Key", key).contentType("application/json").content(legacyRequest))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.quotaLimits[0].limit").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/platform/plans")
                .header("Idempotency-Key", uuidV7(975)).contentType("application/json").content(legacyRequest.replace("legacy-zero", "new-zero")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        for (String invalid : java.util.List.of(
                legacyRequest.replace("\"limit\":0", "\"limit\":-1"),
                legacyRequest.replace("Legacy Zero", " "),
                "{\"code\":\"bad\",\"displayName\":\"Bad\",\"quotaLimits\":[null]}")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/platform/plans")
                    .header("Idempotency-Key", uuidV7(976)).contentType("application/json").content(invalid))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        assertEquals(0, count("entitlement_outbox_events"));
    }

    @BeforeEach
    void clean() throws SQLException {
        executeAsMigrator("TRUNCATE subscription_recovery, plan_recovery, quota_definition_recovery, entitlement_outbox_events, entitlement_bootstrap_idempotency, "
                + "quota_operations, quota_usages, subscriptions, plan_quotas, plans, quota_definitions CASCADE");
    }

    @AfterAll
    void stop() {
        POSTGRES.stop();
    }

    @Autowired
    private RecoverablePlanService recoverablePlans;
    @Autowired
    private PlanQueries planQueries;
    @Autowired
    private PlanRecoveryRepository planRecoveryRepository;

    @Autowired
    private RecoverableQuotaDefinitionService recoverableQuota;

    @Autowired
    private QuotaDefinitionRecoveryRepository quotaRecoveryRepository;
    @Autowired
    private QuotaDefinitionQueries quotaQueries;

    @Test
    void neverRegistersPermanentlyInvalidPlanFieldsAsReplayable() {
        UUID actor = uuidV7(950);
        var definition = service.createQuotaDefinition(actor, uuidV7(951), "max_users", null);
        service.activateQuotaDefinition(actor, uuidV7(952), definition.id(), null);
        assertThrows(io.saas.forge.entitlement.domain.plan.PlanInvalidException.class,
                () -> recoverablePlans.create(actor, uuidV7(953), new PlanDraft("valid", " ", definition.id(), 1), null));
        assertTrue(recoverablePlans.list(actor, null, 50).items().isEmpty());
    }

    @Test
    void rejectsNewZeroPlanAndGrantsButKeepsLegacyPlanReadable() throws SQLException {
        UUID actor = uuidV7(900);
        var quota = service.createQuotaDefinition(actor, uuidV7(901), "max_users", null);
        service.activateQuotaDefinition(actor, uuidV7(902), quota.id(), null);
        assertThrows(io.saas.forge.entitlement.domain.plan.PlanInvalidException.class,
                () -> service.createPlan(actor, uuidV7(903), "zero", "Zero", quota.id(), 0, null));
        assertEquals(0, count("plans"));
        UUID legacy = uuidV7(904);
        executeAsMigrator("INSERT INTO plans VALUES ('" + legacy + "', 'legacy', 'Legacy', 'DRAFT', now(), now())");
        executeAsMigrator("INSERT INTO plan_quotas VALUES ('" + legacy + "', '" + quota.id() + "', 0)");
        assertThrows(io.saas.forge.entitlement.domain.plan.PlanInvalidException.class,
                () -> service.activatePlan(actor, uuidV7(905), legacy, null));
        executeAsMigrator("UPDATE plans SET plan_status='ACTIVE' WHERE id='" + legacy + "'");
        assertThrows(io.saas.forge.entitlement.domain.plan.PlanInvalidException.class,
                () -> initialSubscriptions.create(actor, uuidV7(906), uuidV7(907), legacy, null, null));
        assertEquals(0, count("subscriptions"));
        UUID legacyTenant = uuidV7(908);
        executeAsMigrator("INSERT INTO subscriptions VALUES ('" + uuidV7(909) + "', '" + legacyTenant + "', '" + legacy + "', 'ACTIVE', NULL, now())");
        var rejection = assertThrows(QuotaCommandException.class,
                () -> quotaCommands.consume(uuidV7(910), legacyTenant, "max_users", 1, uuidV7(911), QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION));
        assertEquals(0, rejection.limit());
        assertEquals(0, planQueries.get(legacy).quotaLimits().get(0).limit());
        assertEquals(1, visibleSubscriptionCount(legacyTenant));
    }

    @Test
    void doesNotOfferImpossibleReplayAfterAnotherActorCreatesOrActivatesDefinition() {
        UUID actor = uuidV7(850);
        var created = recoverableQuota.create(actor, uuidV7(851), "max_users", null);
        assertThrows(io.saas.forge.entitlement.domain.quota.QuotaDefinitionAlreadyExistsException.class,
                () -> recoverableQuota.create(uuidV7(852), uuidV7(853), "max_users", null));
        var conflict = recoverableQuota.list(uuidV7(852), null, 50).items().get(0);
        assertEquals(QuotaDefinitionOperation.State.NOT_COMMITTED, conflict.state());
        assertFalse(conflict.canReplay());
        recoverableQuota.activate(actor, uuidV7(854), created.id(), null);
        assertThrows(QuotaDefinitionTransitionException.class,
                () -> recoverableQuota.activate(uuidV7(852), uuidV7(855), created.id(), null));
        assertTrue(recoverableQuota.list(uuidV7(852), null, 50).items().stream().noneMatch(QuotaDefinitionOperation::canReplay));
        assertThrows(IdempotencyKeyReusedException.class,
                () -> recoverableQuota.activate(actor, uuidV7(851), created.id(), null));
    }

    @Test
    void exposesPublishedQuotaReadsAndPrivateRecoveryOverHttp() throws Exception {
        UUID actor = uuidV7(840);
        var controller = new io.saas.forge.entitlement.api.EntitlementBootstrapController(
                authorization -> {
                    if (!"allowed".equals(authorization)) throw new io.saas.forge.sdk.auth.PlatformAuthorizationDeniedException();
                    return actor;
                }, recoverablePlans, planQueries, recoverableSubscriptions, recoverableQuota, quotaQueries, subscriptionQueries);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new io.saas.forge.entitlement.api.EntitlementBootstrapExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/platform/tenants/" + uuidV7(899) + "/subscription").header("Authorization", "allowed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.subscription").isEmpty());
        var created = recoverableQuota.create(actor, uuidV7(841), "max_users", null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/platform/tenants/" + uuidV7(899) + "/subscription-operations").header("Authorization", "allowed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items").isEmpty());
        String definitions = "/api/v1/platform/quota-definitions";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(definitions)
                .header("Authorization", "allowed").param("code", "max").param("status", "DRAFT").param("limit", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].id").value(created.id().toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(definitions + "/" + created.id()).header("Authorization", "allowed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        String operations = "/api/v1/platform/quota-definition-operations";
        var record = recoverableQuota.list(actor, null, 50).items().get(0);
        for (String path : java.util.List.of(definitions, definitions + "/" + created.id(), operations, operations + "/" + record.id(),
                "/api/v1/platform/tenants/" + uuidV7(899) + "/subscription",
                "/api/v1/platform/tenants/" + uuidV7(899) + "/subscription-operations")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(operations).header("Authorization", "allowed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].state").value("COMMITTED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(operations + "/" + record.id() + "/recovery")
                .header("Authorization", "allowed").header("Idempotency-Key", uuidV7(841)).contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.quotaDefinitionId").value(created.id().toString()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(operations + "/" + record.id() + "/recovery")
                .header("Idempotency-Key", uuidV7(841)).contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void exposesPublishedPlanReadsAndPrivateRecoveryOverHttp() throws Exception {
        UUID actor = uuidV7(840);
        var controller = new io.saas.forge.entitlement.api.EntitlementBootstrapController(
                authorization -> {
                    if (!"allowed".equals(authorization)) throw new io.saas.forge.sdk.auth.PlatformAuthorizationDeniedException();
                    return actor;
                }, recoverablePlans, planQueries, recoverableSubscriptions, recoverableQuota, quotaQueries, subscriptionQueries);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new io.saas.forge.entitlement.api.EntitlementBootstrapExceptionHandler()).build();
        var definition = service.createQuotaDefinition(actor, uuidV7(838), "max_users", null);
        service.activateQuotaDefinition(actor, uuidV7(839), definition.id(), null);
        var created = recoverablePlans.create(actor, uuidV7(841), new PlanDraft("starter", "Starter", definition.id(), 1), null);
        String definitions = "/api/v1/platform/plans";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(definitions)
                .header("Authorization", "allowed").param("code", "start").param("status", "DRAFT").param("limit", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].id").value(created.id().toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(definitions + "/" + created.id()).header("Authorization", "allowed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        String operations = "/api/v1/platform/plan-operations";
        var record = recoverablePlans.list(actor, null, 50).items().get(0);
        for (String path : java.util.List.of(definitions, definitions + "/" + created.id(), operations, operations + "/" + record.id(),
                "/api/v1/platform/tenants/" + uuidV7(899) + "/subscription",
                "/api/v1/platform/tenants/" + uuidV7(899) + "/subscription-operations")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(operations).header("Authorization", "allowed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].state").value("COMMITTED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(operations + "/" + record.id() + "/recovery")
                .header("Authorization", "allowed").header("Idempotency-Key", uuidV7(841)).contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.planId").value(created.id().toString()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(operations + "/" + record.id() + "/recovery")
                .header("Idempotency-Key", uuidV7(841)).contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void retainsCommittedFactsButRefusesReplayAtExact24HourBoundary() {
        UUID actor = uuidV7(810);
        var created = recoverableQuota.create(actor, uuidV7(811), "max_users", null);
        var operation = recoverableQuota.list(actor, null, 50).items().get(0);
        var expired = new RecoverableQuotaDefinitionService(service, quotaRecoveryRepository,
                Clock.fixed(operation.replayUntil(), java.time.ZoneOffset.UTC));
        assertEquals(QuotaDefinitionOperation.State.COMMITTED, expired.get(actor, operation.id()).state());
        assertEquals(created.id(), expired.get(actor, operation.id()).quotaDefinitionId());
        assertFalse(expired.get(actor, operation.id()).canReplay());
        assertEquals(null, expired.get(actor, operation.id()).idempotencyKey());
        assertThrows(QuotaDefinitionRecoveryException.class,
                () -> expired.recover(actor, operation.id(), uuidV7(811), null));
        assertThrows(QuotaDefinitionRecoveryException.class,
                () -> expired.create(actor, uuidV7(811), "max_users", null));
        assertEquals(created, quotaQueries.get(created.id()));
        assertEquals(1, quotaQueries.list("max", null, null, 1).items().size());
        assertTrue(quotaQueries.list("absent", null, null, 1).items().isEmpty());
        assertTrue(quotaQueries.list("", io.saas.forge.entitlement.domain.quota.QuotaDefinitionStatus.ACTIVE, null, 1).items().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> quotaQueries.list("", null, "broken", 1));
    }

    @Test
    void retainsPlanResultAfterExact24HoursAndBindsCursorsToFilters() {
        UUID actor = uuidV7(810);
        var definition = service.createQuotaDefinition(actor, uuidV7(808), "max_users", null);
        service.activateQuotaDefinition(actor, uuidV7(809), definition.id(), null);
        var draft = new PlanDraft("starter", "Starter", definition.id(), 1);
        var created = recoverablePlans.create(actor, uuidV7(811), draft, null);
        var operation = recoverablePlans.list(actor, null, 50).items().get(0);
        var expired = new RecoverablePlanService(service, planRecoveryRepository,
                Clock.fixed(operation.replayUntil(), java.time.ZoneOffset.UTC));
        assertEquals(PlanOperation.State.COMMITTED, expired.get(actor, operation.id()).state());
        assertEquals(created.id(), expired.get(actor, operation.id()).planId());
        assertFalse(expired.get(actor, operation.id()).canReplay());
        assertEquals(null, expired.get(actor, operation.id()).idempotencyKey());
        assertThrows(PlanRecoveryException.class,
                () -> expired.recover(actor, operation.id(), uuidV7(811), null));
        assertThrows(PlanRecoveryException.class,
                () -> expired.create(actor, uuidV7(811), draft, null));
        assertEquals(created, planQueries.get(created.id()));
        assertEquals(1, planQueries.list("start", null, null, 1).items().size());
        assertTrue(planQueries.list("absent", null, null, 1).items().isEmpty());
        assertTrue(planQueries.list("", io.saas.forge.entitlement.domain.plan.PlanStatus.ACTIVE, null, 1).items().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> planQueries.list("", null, "broken", 1));
        recoverablePlans.create(actor, uuidV7(812), new PlanDraft("starter-two", "Second", definition.id(), 2), null);
        var firstPage = planQueries.list("start", null, null, 1);
        assertTrue(firstPage.hasMore());
        assertEquals(1, planQueries.list("start", null, firstPage.nextCursor(), 1).items().size());
        assertThrows(IllegalArgumentException.class, () -> planQueries.list("other", null, firstPage.nextCursor(), 1));
    }

    @Test
    void recoversRolledBackCreateAndActivationButNeverRestartsExpiredUnknownAttempt() throws Exception {
        UUID actor = uuidV7(820);
        executeAsMigrator("REVOKE INSERT ON quota_definitions FROM entitlement_app");
        try {
            assertThrows(RuntimeException.class, () -> recoverableQuota.create(actor, uuidV7(821), "max_users", null));
        } finally { executeAsMigrator("GRANT INSERT ON quota_definitions TO entitlement_app"); }
        var operation = recoverableQuota.list(actor, null, 50).items().get(0);
        assertEquals(QuotaDefinitionOperation.State.NOT_COMMITTED, operation.state());
        var expired = new RecoverableQuotaDefinitionService(service, quotaRecoveryRepository,
                Clock.fixed(operation.replayUntil(), java.time.ZoneOffset.UTC));
        assertEquals(QuotaDefinitionOperation.State.UNKNOWN, expired.get(actor, operation.id()).state());
        assertThrows(QuotaDefinitionRecoveryException.class, () -> expired.recover(actor, operation.id(), uuidV7(821), null));
        assertTrue(quotaQueries.list("", null, null, 50).items().isEmpty());
        assertThrows(IdempotencyKeyReusedException.class,
                () -> recoverableQuota.recover(actor, operation.id(), uuidV7(822), null));
        var recovered = recoverableQuota.recover(actor, operation.id(), uuidV7(821), null);
        assertEquals(QuotaDefinitionOperation.State.COMMITTED, recovered.state());
        executeAsMigrator("REVOKE UPDATE ON quota_definitions FROM entitlement_app");
        try {
            assertThrows(RuntimeException.class, () -> recoverableQuota.activate(actor, uuidV7(823), recovered.quotaDefinitionId(), null));
        } finally { executeAsMigrator("GRANT UPDATE ON quota_definitions TO entitlement_app"); }
        var secondPage = recoverableQuota.list(actor, null, 1);
        assertTrue(secondPage.hasMore());
        var activation = recoverableQuota.list(actor, secondPage.nextCursor(), 1).items().get(0);
        assertEquals(QuotaDefinitionOperation.Operation.ACTIVATE, activation.operation());
        assertEquals(recovered.quotaDefinitionId(), activation.quotaDefinitionId());
        assertEquals(QuotaDefinitionOperation.State.NOT_COMMITTED, activation.state());
        recoverableQuota.recover(actor, activation.id(), uuidV7(823), null);
        assertEquals("ACTIVE", quotaQueries.get(recovered.quotaDefinitionId()).status().name());
        assertThrows(IllegalArgumentException.class, () -> recoverableQuota.list(uuidV7(824), secondPage.nextCursor(), 1));
    }

    @Test
    void recoversPlanRollbackWithOriginalKeyAndRejectsExpiredAndOtherActor() throws Exception {
        UUID actor = uuidV7(820);
        var quota = service.createQuotaDefinition(actor, uuidV7(818), "max_users", null);
        service.activateQuotaDefinition(actor, uuidV7(819), quota.id(), null);
        var draft = new PlanDraft("starter", "Starter", quota.id(), 1);
        executeAsMigrator("REVOKE INSERT ON plans FROM entitlement_app");
        try {
            assertThrows(RuntimeException.class, () -> recoverablePlans.create(actor, uuidV7(821), draft, null));
        } finally { executeAsMigrator("GRANT INSERT ON plans TO entitlement_app"); }
        var operation = recoverablePlans.list(actor, null, 50).items().get(0);
        assertTrue(recoverablePlans.list(uuidV7(825), null, 50).items().isEmpty());
        assertThrows(PlanRecoveryException.class, () -> recoverablePlans.get(uuidV7(825), operation.id()));
        assertThrows(PlanRecoveryException.class, () -> recoverablePlans.recover(uuidV7(825), operation.id(), uuidV7(821), null));
        assertEquals(PlanOperation.State.NOT_COMMITTED, operation.state());
        var expired = new RecoverablePlanService(service, planRecoveryRepository,
                Clock.fixed(operation.replayUntil(), java.time.ZoneOffset.UTC));
        assertEquals(PlanOperation.State.UNKNOWN, expired.get(actor, operation.id()).state());
        assertThrows(PlanRecoveryException.class, () -> expired.recover(actor, operation.id(), uuidV7(821), null));
        assertTrue(planQueries.list("", null, null, 50).items().isEmpty());
        assertThrows(IdempotencyKeyReusedException.class,
                () -> recoverablePlans.recover(actor, operation.id(), uuidV7(822), null));
        var recovered = recoverablePlans.recover(actor, operation.id(), uuidV7(821), null);
        assertEquals(PlanOperation.State.COMMITTED, recovered.state());
        executeAsMigrator("REVOKE UPDATE ON plans FROM entitlement_app");
        try {
            assertThrows(RuntimeException.class, () -> recoverablePlans.activate(actor, uuidV7(823), recovered.planId(), null));
        } finally { executeAsMigrator("GRANT UPDATE ON plans TO entitlement_app"); }
        var secondPage = recoverablePlans.list(actor, null, 1);
        assertTrue(secondPage.hasMore());
        var activation = recoverablePlans.list(actor, secondPage.nextCursor(), 1).items().get(0);
        assertEquals(PlanOperation.Operation.ACTIVATE, activation.operation());
        assertEquals(recovered.planId(), activation.planId());
        assertEquals(PlanOperation.State.NOT_COMMITTED, activation.state());
        recoverablePlans.recover(actor, activation.id(), uuidV7(823), null);
        assertEquals("ACTIVE", planQueries.get(recovered.planId()).status().name());
        assertThrows(IllegalArgumentException.class, () -> recoverablePlans.list(uuidV7(824), secondPage.nextCursor(), 1));
    }

    @Test
    void reportsProcessingUnderRealTransactionLockAndRejectsParallelReplay() throws Exception {
        UUID actor = uuidV7(830);
        var saved = quotaRecoveryRepository.prepare(actor, uuidV7(831), QuotaDefinitionOperation.Operation.CREATE,
                null, java.time.Instant.now());
        try (var connection = java.sql.DriverManager.getConnection(jdbcUrl(), "entitlement_app", "entitlement-app-password")) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(hashtextextended('quota-definition-operation:" + saved.id() + "', 0))");
                assertEquals(QuotaDefinitionOperation.State.PROCESSING, recoverableQuota.get(actor, saved.id()).state());
                assertFalse(recoverableQuota.get(actor, saved.id()).canReplay());
                assertThrows(IdempotencyRequestInProgressException.class,
                        () -> recoverableQuota.recover(actor, saved.id(), saved.key(), null));
            } finally { connection.rollback(); }
        }
        assertEquals(QuotaDefinitionOperation.State.NOT_COMMITTED, recoverableQuota.get(actor, saved.id()).state());
        assertEquals(QuotaDefinitionOperation.State.COMMITTED, recoverableQuota.recover(actor, saved.id(), saved.key(), null).state());
    }

    @Test
    void reportsPlanProcessingAndRejectsParallelReplay() throws Exception {
        UUID actor = uuidV7(830);
        var quota = service.createQuotaDefinition(actor, uuidV7(828), "max_users", null);
        service.activateQuotaDefinition(actor, uuidV7(829), quota.id(), null);
        var saved = planRecoveryRepository.prepare(actor, uuidV7(831), PlanOperation.Operation.CREATE,
                null, new PlanDraft("starter", "Starter", quota.id(), 1), java.time.Instant.now());
        try (var connection = java.sql.DriverManager.getConnection(jdbcUrl(), "entitlement_app", "entitlement-app-password")) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(hashtextextended('plan-operation:" + saved.id() + "', 0))");
                assertEquals(PlanOperation.State.PROCESSING, recoverablePlans.get(actor, saved.id()).state());
                assertFalse(recoverablePlans.get(actor, saved.id()).canReplay());
                assertThrows(IdempotencyRequestInProgressException.class,
                        () -> recoverablePlans.recover(actor, saved.id(), saved.key(), null));
            } finally { connection.rollback(); }
        }
        assertEquals(PlanOperation.State.NOT_COMMITTED, recoverablePlans.get(actor, saved.id()).state());
        assertEquals(PlanOperation.State.COMMITTED, recoverablePlans.recover(actor, saved.id(), saved.key(), null).state());
    }

    @Test
    void recoversCreateAndActivationUsingOriginalActorAndKey() {
        UUID actor = uuidV7(801);
        UUID key = uuidV7(802);
        var created = recoverableQuota.create(actor, key, "max_users", null);
        var attempts = recoverableQuota.list(actor, null, 50).items();
        assertEquals(1, attempts.size());
        assertEquals("COMMITTED", attempts.get(0).state().name());
        assertEquals(created.id(), attempts.get(0).quotaDefinitionId());
        assertEquals(created, recoverableQuota.create(actor, key, "max_users", null));
        assertTrue(recoverableQuota.list(uuidV7(803), null, 50).items().isEmpty());
        assertThrows(QuotaDefinitionRecoveryException.class,
                () -> recoverableQuota.get(uuidV7(803), attempts.get(0).id()));
        var active = recoverableQuota.activate(actor, uuidV7(804), created.id(), null);
        assertEquals("ACTIVE", active.status().name());
        assertEquals(2, recoverableQuota.list(actor, null, 50).items().size());
        assertEquals(created, recoverableQuota.create(actor, key, "max_users", null));
    }

    @Test
    void readsAuthoritativeSubscriptionUsageAndRestoresOriginalActorAfterResponseLoss() {
        UUID actor = uuidV7(901), tenant = uuidV7(902), key = uuidV7(903);
        var plan = activePlan(actor, 910, 2);
        assertThrows(IllegalArgumentException.class, () -> recoverableSubscriptions.create(actor, key, tenant,
                plan.id(), java.time.Instant.EPOCH, null));
        assertTrue(recoverableSubscriptions.list(actor, tenant, null, 50).items().isEmpty());
        var created = recoverableSubscriptions.create(actor, key, tenant, plan.id(), null, null);
        var read = subscriptionQueries.get(tenant);
        assertEquals(created, read.subscription());
        assertTrue(read.effective());
        assertEquals(2, read.maxUsersLimit());
        assertEquals(0, read.maxUsersUsed());
        quotaCommands.consume(uuidV7(920), tenant, "max_users", 1, uuidV7(921), QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);
        assertEquals(1, subscriptionQueries.get(tenant).maxUsersUsed());
        quotaCommands.release(uuidV7(920), tenant, "max_users", 1, uuidV7(922), QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);
        assertEquals(0, subscriptionQueries.get(tenant).maxUsersUsed());
        var operation = recoverableSubscriptions.list(actor, tenant, null, 50).items().get(0);
        assertEquals("COMMITTED", operation.state().name());
        assertEquals(created.id(), operation.subscriptionId());
        assertEquals(created.id(), recoverableSubscriptions.recover(actor, operation.id(), key, null).subscriptionId());
        assertTrue(recoverableSubscriptions.list(uuidV7(904), tenant, null, 50).items().isEmpty());
        assertThrows(io.saas.forge.entitlement.application.subscription.SubscriptionRecoveryException.class,
                () -> recoverableSubscriptions.get(uuidV7(904), operation.id()));
        assertThrows(InitialSubscriptionAlreadyExistsException.class,
                () -> recoverableSubscriptions.create(actor, uuidV7(905), tenant, plan.id(), null, null));
        assertEquals(null, subscriptionQueries.get(uuidV7(906)).subscription());
    }

    @Test
    void subscriptionRecoveryExpiresWithoutRecreatingAndKeepsCommittedFacts() {
        UUID actor = uuidV7(930), tenant = uuidV7(931), key = uuidV7(932);
        var plan = activePlan(actor, 940, 1);
        var deadline = java.time.Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var result = recoverableSubscriptions.create(actor, key, tenant, plan.id(), deadline, null);
        var operation = recoverableSubscriptions.list(actor, tenant, null, 1).items().get(0);
        var later = new io.saas.forge.entitlement.application.subscription.RecoverableSubscriptionService(
                initialSubscriptions, subscriptionRecoveryRepository, Clock.fixed(operation.replayUntil(), java.time.ZoneOffset.UTC));
        assertEquals(result.id(), later.get(actor, operation.id()).subscriptionId());
        assertFalse(later.get(actor, operation.id()).canReplay());
        assertThrows(io.saas.forge.entitlement.application.subscription.SubscriptionRecoveryException.class,
                () -> later.recover(actor, operation.id(), key, null));
        var pending = subscriptionRecoveryRepository.prepare(actor, uuidV7(933),
                new io.saas.forge.entitlement.application.subscription.SubscriptionDraft(uuidV7(934), plan.id(), null),
                operation.createdAt());
        assertEquals("UNKNOWN", later.get(actor, pending.id()).state().name());
        assertFalse(later.get(actor, pending.id()).canReplay());
        assertThrows(io.saas.forge.entitlement.application.subscription.SubscriptionRecoveryException.class,
                () -> later.recover(actor, pending.id(), pending.key(), null));
    }

    @Test
    void commitsFourStableOperationsAndAllowlistedOutboxEvents() throws SQLException {
        UUID actor = uuidV7(1);
        QuotaDefinitionResult quota = service.createQuotaDefinition(actor, uuidV7(2), "max_users",
                "11111111111111111111111111111111");
        assertEquals(quota, service.createQuotaDefinition(actor, uuidV7(2), "max_users",
                "11111111111111111111111111111111"));
        service.activateQuotaDefinition(actor, uuidV7(3), quota.id(), null);
        PlanResult plan = service.createPlan(actor, uuidV7(4), "starter", "Starter", quota.id(), 10, null);
        assertEquals(plan, service.createPlan(actor, uuidV7(4), "starter", "Starter", quota.id(), 10, null));
        service.activatePlan(actor, uuidV7(5), plan.id(), null);

        assertEquals(1, count("quota_definitions"));
        assertEquals(1, count("plans"));
        assertEquals(1, count("plan_quotas"));
        assertEquals(4, count("entitlement_bootstrap_idempotency"));
        assertEquals(4, count("entitlement_outbox_events"));
        String snapshots = scalar("SELECT string_agg(event_snapshot::text, ' ') FROM entitlement_outbox_events");
        assertTrue(snapshots.contains("com.saas.forge.quota-definition.created.v1"));
        assertTrue(snapshots.contains("com.saas.forge.quota-definition.activated.v1"));
        assertTrue(snapshots.contains("com.saas.forge.plan.created.v1"));
        assertTrue(snapshots.contains("com.saas.forge.plan.activated.v1"));
        assertFalse(snapshots.contains("displayName"));
        assertFalse(snapshots.contains("quotaLimit"));
    }

    @Test
    void databaseAndStateMachinesRejectDuplicatesAndRepeatedActivation() throws SQLException {
        UUID actor = uuidV7(10);
        QuotaDefinitionResult quota = service.createQuotaDefinition(actor, uuidV7(11), "max_users", null);
        assertThrows(io.saas.forge.entitlement.domain.quota.QuotaDefinitionAlreadyExistsException.class,
                () -> service.createQuotaDefinition(actor, uuidV7(12), "max_users", null));
        service.activateQuotaDefinition(actor, uuidV7(13), quota.id(), null);
        assertThrows(QuotaDefinitionTransitionException.class,
                () -> service.activateQuotaDefinition(actor, uuidV7(14), quota.id(), null));

        PlanResult plan = service.createPlan(actor, uuidV7(15), "starter", "Starter", quota.id(), 1, null);
        assertThrows(io.saas.forge.entitlement.domain.plan.PlanAlreadyExistsException.class,
                () -> service.createPlan(actor, uuidV7(16), "starter", "Other", quota.id(), 2, null));
        service.activatePlan(actor, uuidV7(17), plan.id(), null);
        assertThrows(PlanTransitionException.class,
                () -> service.activatePlan(actor, uuidV7(18), plan.id(), null));

        assertEquals(1, count("quota_definitions"));
        assertEquals(1, count("plans"));
        assertEquals(4, count("entitlement_outbox_events"));
    }

    @Test
    void concurrentSameCallerAndKeyCreatesExactlyOneDefinition() throws Exception {
        UUID actor = uuidV7(20);
        UUID key = uuidV7(21);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<QuotaDefinitionResult> first = executor.submit(() -> {
                start.await();
                return service.createQuotaDefinition(actor, key, "max_users", null);
            });
            Future<QuotaDefinitionResult> second = executor.submit(() -> {
                start.await();
                return service.createQuotaDefinition(actor, key, "max_users", null);
            });
            start.countDown();
            assertEquals(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("quota_definitions"));
        assertEquals(1, count("entitlement_outbox_events"));
    }

    @Test
    void outboxFailureRollsBackDomainStateAndIdempotency() throws SQLException {
        executeAsMigrator("""
                CREATE OR REPLACE FUNCTION fail_entitlement_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced outbox failure'; END $$;
                CREATE TRIGGER fail_entitlement_outbox BEFORE INSERT ON entitlement_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_entitlement_outbox()
                """);
        try {
            assertThrows(RuntimeException.class,
                    () -> service.createQuotaDefinition(uuidV7(30), uuidV7(31), "max_users", null));
            assertEquals(0, count("quota_definitions"));
            assertEquals(0, count("entitlement_bootstrap_idempotency"));
            assertEquals(0, count("entitlement_outbox_events"));
        } finally {
            executeAsMigrator("DROP TRIGGER fail_entitlement_outbox ON entitlement_outbox_events; "
                    + "DROP FUNCTION fail_entitlement_outbox()");
        }
    }

    @Test
    void platformGlobalTablesDoNotFabricateTenantScopeAndRuntimeRoleCannotBypassRls() throws SQLException {
        assertEquals(0, Integer.parseInt(scalar("""
                SELECT count(*)::text
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('quota_definitions', 'plans', 'plan_quotas')
                  AND column_name = 'tenant_id'
                """)));
        assertFalse(Boolean.parseBoolean(scalar(
                "SELECT rolbypassrls::text FROM pg_roles WHERE rolname = 'entitlement_app'")));
        try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("SET ROLE entitlement_migrator"));
        }
    }

    @Test
    void createsOnlyOneInitialSubscriptionWithStableReplayConcurrentExclusionAndRls() throws Exception {
        UUID actor = uuidV7(40);
        UUID tenant = uuidV7(41);
        UUID otherTenant = uuidV7(42);
        PlanResult plan = activePlan(actor, 43);
        var created = initialSubscriptions.create(
                actor, uuidV7(50), tenant, plan.id(), null, null);
        assertEquals(created, initialSubscriptions.create(
                actor, uuidV7(50), tenant, plan.id(), null, null));
        assertThrows(InitialSubscriptionAlreadyExistsException.class,
                () -> initialSubscriptions.create(actor, uuidV7(51), tenant, plan.id(), null, null));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> concurrentCreate(
                    start, actor, uuidV7(52), otherTenant, plan.id()));
            Future<Boolean> second = executor.submit(() -> concurrentCreate(
                    start, actor, uuidV7(53), otherTenant, plan.id()));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, count("subscriptions"));
        assertEquals(1, visibleSubscriptionCount(tenant));
        assertEquals(1, visibleSubscriptionCount(otherTenant));
        String snapshots = scalar("SELECT string_agg(event_snapshot::text, ' ') FROM entitlement_outbox_events");
        assertTrue(snapshots.contains("com.saas.forge.subscription.created.v1"));
    }

    @Test
    void subscriptionOutboxFailureRollsBackSubscriptionAndIdempotency() throws SQLException {
        UUID actor = uuidV7(60);
        UUID tenant = uuidV7(61);
        PlanResult plan = activePlan(actor, 62);
        int idempotencyBefore = count("entitlement_bootstrap_idempotency");
        int outboxBefore = count("entitlement_outbox_events");
        executeAsMigrator("""
                CREATE OR REPLACE FUNCTION fail_entitlement_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced outbox failure'; END $$;
                CREATE TRIGGER fail_entitlement_outbox BEFORE INSERT ON entitlement_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_entitlement_outbox()
                """);
        try {
            assertThrows(RuntimeException.class,
                    () -> recoverableSubscriptions.create(
                            actor, uuidV7(66), tenant, plan.id(), null, null));
            assertEquals(0, count("subscriptions"));
            assertEquals(idempotencyBefore, count("entitlement_bootstrap_idempotency"));
            assertEquals(outboxBefore, count("entitlement_outbox_events"));
        } finally {
            executeAsMigrator("DROP TRIGGER fail_entitlement_outbox ON entitlement_outbox_events; "
                    + "DROP FUNCTION fail_entitlement_outbox()");
        }
        var operation = recoverableSubscriptions.list(actor, tenant, null, 50).items().get(0);
        assertEquals("NOT_COMMITTED", operation.state().name());
        assertTrue(operation.canReplay());
        assertEquals("COMMITTED", recoverableSubscriptions.recover(actor, operation.id(), uuidV7(66), null).state().name());
        assertEquals(0, subscriptionQueries.get(tenant).maxUsersUsed());
    }

    @Test
    void atomicallyConsumesAndReleasesWithStableReplayAndPurposeEvents() throws SQLException {
        UUID actor = uuidV7(70);
        UUID tenant = uuidV7(71);
        UUID caller = uuidV7(72);
        PlanResult plan = activePlan(actor, 73, 1);
        initialSubscriptions.create(actor, uuidV7(77), tenant, plan.id(), null, null);

        UUID consumeOperation = uuidV7(78);
        var consumed = quotaCommands.consume(caller, tenant, "max_users", 1, consumeOperation,
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);
        assertEquals(1, consumed.usage());
        assertEquals(1, consumed.limit());
        assertFalse(consumed.replayed());
        assertTrue(quotaCommands.consume(caller, tenant, "max_users", 1, consumeOperation,
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION).replayed());
        assertThrows(QuotaOperationIdReusedException.class,
                () -> quotaCommands.release(caller, tenant, "max_users", 1, consumeOperation,
                        QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION));

        UUID releaseOperation = uuidV7(79);
        assertEquals(0, quotaCommands.release(caller, tenant, "max_users", 1, releaseOperation,
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION).usage());
        assertTrue(quotaCommands.release(caller, tenant, "max_users", 1, releaseOperation,
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION).replayed());

        QuotaCommandException underflow = assertThrows(QuotaCommandException.class,
                () -> quotaCommands.release(caller, tenant, "max_users", 1, uuidV7(80),
                        QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION));
        assertEquals(QuotaOperationOutcome.QUOTA_RELEASE_UNDERFLOW, underflow.outcome());
        String events = scalar("SELECT string_agg(event_snapshot::text, ' ') FROM entitlement_outbox_events");
        assertTrue(events.contains("com.saas.forge.quota.consumed.v1"));
        assertTrue(events.contains("com.saas.forge.quota.released.v1"));
        assertTrue(events.contains("TENANT_ADMIN_INITIALIZATION"));
        assertFalse(events.contains(caller.toString()));
    }

    @Test
    void membershipDisableCanOnlyReleaseOneSeatWithStableOperationId() throws SQLException {
        UUID actor = uuidV7(170);
        UUID tenant = uuidV7(171);
        UUID caller = uuidV7(172);
        PlanResult plan = activePlan(actor, 173, 1);
        initialSubscriptions.create(actor, uuidV7(177), tenant, plan.id(), null, null);
        quotaCommands.consume(caller, tenant, "max_users", 1, uuidV7(178),
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);

        assertThrows(IllegalArgumentException.class,
                () -> quotaCommands.consume(caller, tenant, "max_users", 1, uuidV7(179),
                        QuotaOperationPurpose.MEMBERSHIP_DISABLE));
        UUID releaseId = uuidV7(180);
        assertEquals(0, quotaCommands.release(caller, tenant, "max_users", 1, releaseId,
                QuotaOperationPurpose.MEMBERSHIP_DISABLE).usage());
        assertTrue(quotaCommands.release(caller, tenant, "max_users", 1, releaseId,
                QuotaOperationPurpose.MEMBERSHIP_DISABLE).replayed());
        assertEquals(0, subscriptionQueries.get(tenant).maxUsersUsed());
        assertTrue(scalar("SELECT string_agg(event_snapshot::text, ' ') FROM entitlement_outbox_events")
                .contains("MEMBERSHIP_DISABLE"));
    }

    @Test
    void concurrentConsumeNeverExceedsCurrentSubscriptionLimit() throws Exception {
        UUID actor = uuidV7(90);
        UUID tenant = uuidV7(91);
        UUID caller = uuidV7(92);
        PlanResult plan = activePlan(actor, 93, 3);
        initialSubscriptions.create(actor, uuidV7(97), tenant, plan.id(), null, null);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            var futures = new java.util.ArrayList<Future<Boolean>>();
            for (int index = 0; index < 12; index++) {
                UUID operationId = uuidV7(100 + index);
                futures.add(executor.submit(() -> concurrentConsume(start, caller, tenant, operationId)));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) {
                successes += future.get() ? 1 : 0;
            }
            assertEquals(3, successes);
        } finally {
            executor.shutdownNow();
        }
        assertEquals("3", scalar("SELECT used::text FROM quota_usages WHERE tenant_id = '" + tenant + "'"));
        assertEquals(12, count("quota_operations"));
    }

    @Test
    void outboxFailureRollsBackQuotaUsageAndOperationAndRlsHidesOtherTenants() throws SQLException {
        UUID actor = uuidV7(120);
        UUID tenant = uuidV7(121);
        UUID otherTenant = uuidV7(122);
        UUID caller = uuidV7(123);
        PlanResult plan = activePlan(actor, 124, 2);
        initialSubscriptions.create(actor, uuidV7(128), tenant, plan.id(), null, null);
        initialSubscriptions.create(actor, uuidV7(129), otherTenant, plan.id(), null, null);

        executeAsMigrator("""
                CREATE OR REPLACE FUNCTION fail_quota_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_snapshot->>'type' = 'com.saas.forge.quota.consumed.v1' THEN
                        RAISE EXCEPTION 'forced quota outbox failure';
                    END IF;
                    RETURN NEW;
                END $$;
                CREATE TRIGGER fail_quota_outbox BEFORE INSERT ON entitlement_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_quota_outbox()
                """);
        try {
            assertThrows(RuntimeException.class,
                    () -> quotaCommands.consume(caller, tenant, "max_users", 1, uuidV7(130),
                            QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION));
            assertEquals(0, count("quota_usages"));
            assertEquals(0, count("quota_operations"));
        } finally {
            executeAsMigrator("DROP TRIGGER fail_quota_outbox ON entitlement_outbox_events; "
                    + "DROP FUNCTION fail_quota_outbox()");
        }

        quotaCommands.consume(caller, tenant, "max_users", 1, uuidV7(131),
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);
        quotaCommands.consume(caller, otherTenant, "max_users", 1, uuidV7(132),
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);
        assertEquals(1, visibleTenantRows("quota_usages", tenant));
        assertEquals(1, visibleTenantRows("quota_usages", otherTenant));
        assertEquals(1, visibleTenantRows("quota_operations", tenant));
        assertEquals(1, visibleTenantRows("quota_operations", otherTenant));
        assertEquals(0, visibleTenantRowsWithoutTarget("quota_operations"));
    }

    @Test
    void grpcRejectsWrongScopeAndPurposeBeforeQuotaStateAndReturnsContractFields() throws Exception {
        ServiceAccessTokenAuthorizer tokens = mock(ServiceAccessTokenAuthorizer.class);
        UUID caller = uuidV7(140);
        when(tokens.authorize("tenant-access-token", "entitlement:quota:write"))
                .thenReturn(new ServiceAccessAuthorization(
                        caller, java.util.Set.of("entitlement:quota:write")));
        doThrow(new ServiceAccessTokenScopeException())
                .when(tokens).authorize("runtime-token", "entitlement:quota:write");
        QuotaCommandGrpcService grpc = new QuotaCommandGrpcService(quotaCommands);
        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName).directExecutor()
                .addService(ServerInterceptors.intercept(grpc, new QuotaCommandServerInterceptor(tokens)))
                .build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        try {
            StatusRuntimeException wrongScope = assertThrows(StatusRuntimeException.class,
                    () -> quotaStub(channel, "runtime-token").consume(quotaRequest(uuidV7(142), uuidV7(143),
                            QuotaPurpose.TENANT_ADMIN_INITIALIZATION)));
            assertEquals(Status.Code.PERMISSION_DENIED, wrongScope.getStatus().getCode());
            assertEquals(0, count("quota_operations"));

            StatusRuntimeException wrongPurpose = assertThrows(StatusRuntimeException.class,
                    () -> quotaStub(channel, "tenant-access-token").consume(
                            quotaRequest(uuidV7(144), uuidV7(145), QuotaPurpose.RUNTIME)));
            assertEquals(Status.Code.INVALID_ARGUMENT, wrongPurpose.getStatus().getCode());
            assertEquals(0, count("quota_operations"));

            UUID actor = uuidV7(146);
            UUID tenant = uuidV7(147);
            PlanResult plan = activePlan(actor, 148, 2);
            initialSubscriptions.create(actor, uuidV7(152), tenant, plan.id(), null, null);
            var response = quotaStub(channel, "tenant-access-token").consume(
                    quotaRequest(tenant, uuidV7(153), QuotaPurpose.TENANT_ADMIN_INITIALIZATION));
            assertEquals(1, response.getUsage());
            assertEquals(2, response.getLimit());
            assertFalse(response.getReplayed());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void releaseStillCompensatesAfterSubscriptionExpires() throws SQLException {
        UUID actor = uuidV7(160);
        UUID tenant = uuidV7(161);
        UUID caller = uuidV7(162);
        PlanResult plan = activePlan(actor, 163, 1);
        initialSubscriptions.create(actor, uuidV7(167), tenant, plan.id(), null, null);
        quotaCommands.consume(caller, tenant, "max_users", 1, uuidV7(168),
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);
        executeAsMigrator("UPDATE subscriptions SET ends_at = created_at + interval '1 millisecond' "
                + "WHERE tenant_id = '" + tenant + "'");

        assertEquals(0, quotaCommands.release(caller, tenant, "max_users", 1, uuidV7(169),
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION).usage());
    }

    private boolean concurrentCreate(
            CountDownLatch start, UUID actor, UUID key, UUID tenantId, UUID planId) throws InterruptedException {
        start.await();
        try {
            initialSubscriptions.create(actor, key, tenantId, planId, null, null);
            return true;
        } catch (InitialSubscriptionAlreadyExistsException exception) {
            return false;
        }
    }

    private boolean concurrentConsume(
            CountDownLatch start, UUID caller, UUID tenantId, UUID operationId) throws InterruptedException {
        start.await();
        try {
            quotaCommands.consume(caller, tenantId, "max_users", 1, operationId,
                    QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);
            return true;
        } catch (QuotaCommandException exception) {
            assertEquals(QuotaOperationOutcome.QUOTA_EXCEEDED, exception.outcome());
            return false;
        }
    }

    private PlanResult activePlan(UUID actor, long seed) {
        return activePlan(actor, seed, 10);
    }

    private PlanResult activePlan(UUID actor, long seed, int limit) {
        QuotaDefinitionResult quota = service.createQuotaDefinition(actor, uuidV7(seed), "max_users", null);
        service.activateQuotaDefinition(actor, uuidV7(seed + 1), quota.id(), null);
        PlanResult plan = service.createPlan(
                actor, uuidV7(seed + 2), "starter-" + seed, "Starter", quota.id(), limit, null);
        return service.activatePlan(actor, uuidV7(seed + 3), plan.id(), null);
    }

    private static int visibleTenantRows(String table, UUID tenantId) throws SQLException {
        try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', true)");
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static int visibleTenantRowsWithoutTarget(String table) throws SQLException {
        try (Connection connection = appConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static QuotaCommandRequest quotaRequest(
            UUID tenantId, UUID operationId, QuotaPurpose purpose) {
        return QuotaCommandRequest.newBuilder()
                .setTenantId(tenantId.toString())
                .setQuotaCode("max_users")
                .setAmount(1)
                .setOperationId(operationId.toString())
                .setPurpose(purpose)
                .build();
    }

    private static QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub quotaStub(
            ManagedChannel channel, String token) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return QuotaCommandServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private static int visibleSubscriptionCount(UUID tenantId) throws SQLException {
        try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', true)");
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM subscriptions")) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static int count(String table) throws SQLException {
        return Integer.parseInt(scalar("SELECT count(*)::text FROM " + table));
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection connection = migratorConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void executeAsMigrator(String sql) throws SQLException {
        try (Connection connection = migratorConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection migratorConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                jdbcUrl(), "entitlement_migrator", "entitlement-migrator-password");
    }

    private static Connection appConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                jdbcUrl(), "entitlement_app", "entitlement-app-password");
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/entitlement_db";
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
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
            basePackages = "io.saas.forge.entitlement.infrastructure.persistence.mapper",
            sqlSessionFactoryRef = "entitlementSqlSessionFactory")
    @Import({
            io.saas.forge.entitlement.application.subscription.RecoverableSubscriptionService.class,
            io.saas.forge.entitlement.infrastructure.persistence.MyBatisSubscriptionRecovery.class,
            io.saas.forge.entitlement.infrastructure.persistence.MyBatisSubscriptionQueries.class,
            RecoverablePlanService.class,
            io.saas.forge.entitlement.infrastructure.persistence.MyBatisPlanRecovery.class,
            io.saas.forge.entitlement.infrastructure.persistence.MyBatisPlanQueries.class,
            RecoverableQuotaDefinitionService.class,
            io.saas.forge.entitlement.infrastructure.persistence.MyBatisQuotaDefinitionQueries.class,
            io.saas.forge.entitlement.infrastructure.persistence.MyBatisQuotaDefinitionRecovery.class,
            MyBatisQuotaDefinitionRepository.class,
            MyBatisQuotaOperationRepository.class,
            MyBatisPlanRepository.class,
            MyBatisSubscriptionRepository.class,
            MyBatisEntitlementBootstrapIdempotency.class,
            MyBatisEntitlementOutboxEventRepository.class
    })
    static class PersistenceConfiguration {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(jdbcUrl(), "entitlement_app", "entitlement-app-password");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory entitlementSqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/*Mapper.xml"));
            factory.setTypeHandlersPackage("io.saas.forge.entitlement.infrastructure.persistence.type");
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory entitlementSqlSessionFactory) {
            return new SqlSessionTemplate(entitlementSqlSessionFactory);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        UuidV7Generator ids(Clock clock) {
            return new UuidV7Generator(clock, new SecureRandom());
        }

        @Bean
        EntitlementEventFactory events(ObjectMapper objectMapper, UuidV7Generator ids) {
            return new EntitlementEventFactory(
                    objectMapper, ids, "saas.forge.test.entitlement-service.events");
        }

        @Bean
        EntitlementBootstrapService service(
                MyBatisQuotaDefinitionRepository quotaDefinitions,
                MyBatisPlanRepository plans,
                MyBatisEntitlementBootstrapIdempotency idempotency,
                MyBatisEntitlementOutboxEventRepository outbox,
                EntitlementEventFactory events,
                UuidV7Generator ids,
                Clock clock) {
            return new EntitlementBootstrapService(
                    quotaDefinitions, plans, idempotency, outbox, events, ids, clock);
        }

        @Bean
        TenantEligibilityGateway tenantEligibilityGateway() {
            return tenantId -> TenantEligibilityGateway.Outcome.PENDING_ELIGIBLE;
        }

        @Bean
        CreateInitialSubscriptionService initialSubscriptions(
                MyBatisPlanRepository plans,
                MyBatisSubscriptionRepository subscriptions,
                TenantEligibilityGateway tenantEligibility,
                MyBatisEntitlementBootstrapIdempotency idempotency,
                MyBatisEntitlementOutboxEventRepository outbox,
                EntitlementEventFactory events,
                UuidV7Generator ids,
                Clock clock) {
            return new CreateInitialSubscriptionService(
                    plans, subscriptions, tenantEligibility, idempotency, outbox, events, ids, clock);
        }

        @Bean
        QuotaCommandApplicationService quotaCommands(
                MyBatisQuotaOperationRepository operations,
                MyBatisEntitlementOutboxEventRepository outbox,
                EntitlementEventFactory events,
                Clock clock) {
            return new QuotaCommandApplicationService(operations, outbox, events, clock);
        }
    }
}
