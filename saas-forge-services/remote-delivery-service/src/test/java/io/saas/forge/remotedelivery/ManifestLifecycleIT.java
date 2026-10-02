package io.saas.forge.remotedelivery;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class ManifestLifecycleIT {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18");
    private static final UUID CI = UUID.fromString("018f2d3a-4b5c-7d6e-8f90-123456789abc");
    private static final UUID ADMIN = UUID.fromString("018f2d3a-4b5c-7d6e-8f90-123456789abd");
    private static ManifestService service;
    private static ManifestRepository repository;
    private static TransactionTemplate transaction;
    private static JdbcClient runtime;
    private static org.springframework.context.annotation.AnnotationConfigApplicationContext context;
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Transactions {}
    @BeforeAll static void setup() {
        var admin = new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        JdbcClient.create(admin).sql("CREATE ROLE remote_delivery_app LOGIN PASSWORD 'isolated-test-only'").update();
        Flyway.configure().dataSource(admin).locations("classpath:db/migration").load().migrate();
        var app = new DriverManagerDataSource(DATABASE.getJdbcUrl(),"remote_delivery_app","isolated-test-only");
        runtime = JdbcClient.create(app);
        repository = new ManifestRepository(runtime,JsonMapper.builder().build());
        context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(app));
        context.registerBean(ManifestService.class, () -> new ManifestService(repository,
                new ManifestPolicy("saas.forge.test",Map.of("project",CI)),Clock.systemUTC()));
        context.refresh();
        service = context.getBean(ManifestService.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(app));
    }
    @org.junit.jupiter.api.AfterAll static void closeContext() { if (context != null) context.close(); }
    @Test void springProxyOwnsRegistrationTransaction() {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isTrue();
        var created = service.register(CI, declaration("4.0.0"));
        assertThat(runtime.sql("SELECT count(*) FROM remote_manifest_facts WHERE manifest_id=:id")
                .param("id",created.id()).query(Integer.class).single()).isEqualTo(1);
    }
    @Test void registrationIsImmutableAndDoesNotEnableCode() {
        var request = declaration("1.0.0");
        var created = transaction.execute(status -> service.register(CI,request));
        assertThat(created.state()).isEqualTo(RemoteManifest.State.PENDING_REVIEW);
        var replay = transaction.execute(status -> service.register(CI,request));
        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(runtime.sql("SELECT count(*) FROM remote_manifest_facts WHERE manifest_id=:id").param("id",created.id()).query(Integer.class).single()).isEqualTo(1);
        var changed = new RegisterManifestRequest("project","1.0.0",request.source(),"different","b".repeat(64));
        assertThatThrownBy(() -> transaction.execute(status -> service.register(CI,changed))).isInstanceOf(ManifestException.class).hasMessage("MANIFEST_VERSION_CONFLICT");
        assertThatThrownBy(() -> transaction.execute(status -> service.register(ADMIN,declaration("1.0.1")))).hasMessage("CI_MODULE_NOT_ALLOWED");
        var foreign = new RegisterManifestRequest("project","1.0.1","https://outside.example/remote.js","1.4.0","a".repeat(64));
        assertThatThrownBy(() -> transaction.execute(status -> service.register(CI,foreign))).hasMessage("REMOTE_SOURCE_NOT_ALLOWED");
    }
    @Test void reviewEnableAndRetryHaveCommittedFactsAndScopeBoundCursor() {
        var first = transaction.execute(status -> service.register(CI,declaration("2.0.0")));
        assertThatThrownBy(() -> decide(first.id(),key(),"ENABLE")).hasMessage("MANIFEST_STATE_CONFLICT");
        var approval = key();
        var reviewed = decide(first.id(),approval,"APPROVE");
        assertThat(reviewed.state()).isEqualTo(RemoteManifest.State.APPROVED);
        var enabled = decide(first.id(),key(),"ENABLE");
        assertThat(enabled.state()).isEqualTo(RemoteManifest.State.ENABLED);
        assertThat(enabled.enabledBy()).isEqualTo(ADMIN);
        assertThat(decide(first.id(),approval,"APPROVE")).isEqualTo(reviewed);
        assertThatThrownBy(() -> decide(first.id(),approval,"REJECT")).hasMessage("IDEMPOTENCY_KEY_REUSED");
        var second = transaction.execute(status -> service.register(CI,declaration("2.0.1")));
        decide(second.id(),key(),"APPROVE");
        assertThatThrownBy(() -> decide(second.id(),key(),"ENABLE")).hasMessage("MODULE_ALREADY_ENABLED");
        var rejected = transaction.execute(status -> service.register(CI,declaration("2.0.2")));
        decide(rejected.id(),key(),"REJECT");
        assertThatThrownBy(() -> decide(rejected.id(),key(),"ENABLE")).hasMessage("MODULE_ALREADY_ENABLED");
        var page = service.list(null,1,ADMIN.toString(),false);
        assertThat(page.hasMore()).isTrue();
        assertThatThrownBy(() -> service.list(page.nextCursor(),1,CI.toString(),false)).hasMessage("INVALID_PAGE");
        assertThat(service.list(null,100,CI.toString(),true).items()).extracting(RemoteManifest::id).containsExactly(first.id());
        assertThat(runtime.sql("SELECT count(*) FROM remote_manifest_facts WHERE manifest_id=:id").param("id",first.id()).query(Integer.class).single()).isEqualTo(3);
    }
    @Test void runtimeCannotRewriteDeclarationsOrFactsAndRollbackLeavesNoFact() {
        var manifest = transaction.execute(status -> service.register(CI,declaration("3.0.0")));
        assertThatThrownBy(() -> runtime.sql("UPDATE remote_manifests SET source='https://outside.example' WHERE id=:id").param("id",manifest.id()).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> runtime.sql("UPDATE remote_manifest_facts SET action='ENABLE' WHERE manifest_id=:id").param("id",manifest.id()).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> transaction.execute(status -> {
            service.register(CI,declaration("3.0.1"));throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(repository.version("project","3.0.1")).isEmpty();
    }
    private static RemoteManifest decide(UUID id,UUID key,String action) { return transaction.execute(status -> service.decide(ADMIN,key,id,action)); }
    private static RegisterManifestRequest declaration(String version) { return new RegisterManifestRequest("project",version,"https://remote.saas.forge.test/project/"+version+"/remote.js","1.4.0","a".repeat(64)); }
    private static UUID key() { return runtime.sql("SELECT uuidv7()").query(UUID.class).single(); }
}
