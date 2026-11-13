package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.demo.category.mapper.OutboxAuditMapper;
import io.saasforge.demo.order.model.OrderImportRow;
import io.saasforge.demo.order.model.OrderOverwriteRow;
import io.saasforge.demo.order.service.OrderIoService;
import io.saasforge.demo.order.service.OrderService;
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.abac.data.DataPermission;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditOutcome;
import io.saasforge.sdk.core.CurrentActorProvider;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.ReferenceDataKeys;
import io.saasforge.sdk.core.SubjectAttributes;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.io.ExcelColumn;
import io.saasforge.sdk.io.ExcelIo;
import io.saasforge.sdk.io.ImportBackup;
import io.saasforge.sdk.io.ImportBackups;
import io.saasforge.sdk.io.ImportRollbackReason;
import io.saasforge.sdk.io.ImportRollbackWriter;
import io.saasforge.sdk.io.ImportRollbacks;
import io.saasforge.sdk.io.ImportSnapshot;
import io.saasforge.sdk.io.ImportWriter;
import io.saasforge.sdk.io.IoAutoConfiguration;
import io.saasforge.sdk.io.IoMaintenance;
import io.saasforge.sdk.io.IoStoragePurposes;
import io.saasforge.sdk.io.IoTask;
import io.saasforge.sdk.io.IoTasks;
import io.saasforge.sdk.manifest.ResourceManifestCollector;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantDeleted;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import io.saasforge.sdk.security.EffectiveFieldPolicy;
import io.saasforge.sdk.security.MaskingRule;
import io.saasforge.sdk.security.SensitiveField;
import io.saasforge.sdk.security.TenantContext;
import io.saasforge.sdk.storage.ObjectStorage;
import io.saasforge.sdk.storage.StorageErrorCode;
import io.saasforge.sdk.storage.StorageObjectStatus;
import io.saasforge.sdk.storage.StoragePurpose;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.json.JsonMapper;

/** 订单附件经 SDK 对象存储上传、下载与删除（工单 24）：key 前缀由 SDK 强制、跨租户访问被拒、超出存储容量 Quota 的上传被拒。 */
@Testcontainers
@Import(ObjectStorageTest.RetentionClockConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:storage-purpose-migration",
            "forge.storage.retention.batch-size=2",
            "forge.io.workers=1",
            "forge.io.queue-capacity=1",
            "forge.io.import-limit=3",
            "forge.io.export-limit=3",
            "forge.io.heartbeat-interval=100ms",
            "forge.io.heartbeat-timeout=1s",
            "forge.io.retention=2s"
        })
class ObjectStorageTest {
    private static final String BUCKET = "forge-test";
    private static final String ACCESS_KEY = "storage-test";
    private static final String SECRET_KEY = "storage-test-secret";

    static final SharedContainers.Postgres postgres = SharedContainers.postgres();

    static final SharedContainers.Redis redis = SharedContainers.redis();

    // 与本地 compose 相同的 S3 兼容实现；SDK 只依赖 S3 协议（ADR-0011）
    @Container
    static final GenericContainer<?> s3 = new GenericContainer<>("pgsty/silo:RELEASE.2026-10-18T00-00-00Z")
            .withCommand("silo", "server", "/data")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.storage.endpoint", ObjectStorageTest::endpoint);
        registry.add("forge.storage.bucket", () -> BUCKET);
        registry.add("forge.storage.access-key", () -> ACCESS_KEY);
        registry.add("forge.storage.secret-key", () -> SECRET_KEY);
        TestTokens.register(registry);
        TestExportMasking.register(registry);
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client client = s3Client()) {
            client.createBucket(request -> request.bucket(BUCKET));
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    ObjectStorage storage;

    @Autowired
    io.saasforge.sdk.storage.FileReferenceChecks fileReferenceChecks;

    @Autowired
    IoTasks ioTasks;

    @Autowired
    ExcelIo excel;

    @Autowired
    IoMaintenance maintenance;

    @Autowired
    ImportBackups backups;

    @Autowired
    ImportRollbacks rollbacks;

    @Autowired
    MutableStorageClock retentionClock;

    @TestConfiguration(proxyBeanMethods = false)
    static class RetentionClockConfiguration {
        @Bean
        MutableStorageClock storageClock() {
            return new MutableStorageClock();
        }
    }

    static final class MutableStorageClock extends Clock {
        private volatile Instant current = Instant.now();

        void at(String instant) {
            current = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private UUID tenant;

    @BeforeEach
    void newTenant() {
        retentionClock.current = Instant.now();
        tenant = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID);
    }

    @Test
    void retentionDeadlineExpiresWithoutDeletingTheFileOrReleasingQuota() {
        var purpose = new StoragePurpose("temporary-document", Set.of("pdf"), 4, Duration.ofMinutes(2), 2);
        retentionClock.at("2026-11-01T00:00:00Z");
        try (var ignored = CurrentTenant.bind(tenant)) {
            UUID id = storage.write(purpose, "temporary.pdf", null, 4, new ByteArrayInputStream(new byte[4]))
                    .id();
            assertThat(storage.lifecycle(id).retentionExpiresAt()).isEqualTo("2026-11-03T00:00:00Z");
            retentionClock.at("2026-11-02T23:59:59Z");
            assertThat(storage.expire(id)).isFalse();
            assertThat(storage.get(purpose, id).size()).isEqualTo(4);
            retentionClock.at("2026-11-03T00:00:00Z");
            assertThat(storage.expire(id)).isTrue();
            assertThat(storage.lifecycle(id).status()).isEqualTo(StorageObjectStatus.EXPIRED);
            assertThat(storage.lifecycle(id).expiredAt()).isEqualTo("2026-11-03T00:00:00Z");
            assertThat(storage.lifecycle(id).purgeAfter()).isEqualTo("2026-11-10T00:00:00Z");
            assertThat(storage.usage().usedBytes()).isEqualTo(4);
            assertThat(objectKeys()).contains(tenant + "/demo-service/" + id);
            assertThatThrownBy(() -> storage.get(purpose, id)).isInstanceOf(ForgeException.class);
            assertThatThrownBy(() -> storage.completeUpload(id)).isInstanceOf(ForgeException.class);
        }
    }

    @Test
    void expiredFileCanBeRestoredDuringGraceWithAFreshDeclaredRetention() {
        var purpose = new StoragePurpose("temporary-document", Set.of("pdf"), 4, Duration.ofMinutes(2), 2);
        retentionClock.at("2026-11-01T00:00:00Z");
        try (var ignored = CurrentTenant.bind(tenant)) {
            UUID id = storage.write(purpose, "temporary.pdf", null, 4, new ByteArrayInputStream(new byte[4]))
                    .id();
            retentionClock.at("2026-11-03T00:00:00Z");
            storage.expire(id);
            retentionClock.at("2026-11-09T00:00:00Z");
            assertThat(storage.restore(purpose, id).status()).isEqualTo(StorageObjectStatus.STORED);
            assertThat(storage.lifecycle(id).retentionExpiresAt()).isEqualTo("2026-11-11T00:00:00Z");
            assertThat(storage.lifecycle(id).expiredAt()).isNull();
            assertThat(storage.get(purpose, id).size()).isEqualTo(4);
            assertThat(storage.usage().usedBytes()).isEqualTo(4);
        }
    }

    @Test
    void retentionExtensionReactivatesAnExpiredFileButCannotShortenOrBypassGrace() {
        var purpose = new StoragePurpose("temporary-document", Set.of("pdf"), 4, Duration.ofMinutes(2), 2);
        retentionClock.at("2026-11-01T00:00:00Z");
        try (var ignored = CurrentTenant.bind(tenant)) {
            UUID id = storage.write(purpose, "temporary.pdf", null, 4, new ByteArrayInputStream(new byte[4]))
                    .id();
            retentionClock.at("2026-11-03T00:00:00Z");
            storage.expire(id);
            assertThat(storage.extendRetention(id, OffsetDateTime.parse("2026-12-01T00:00:00Z"))
                            .status())
                    .isEqualTo(StorageObjectStatus.STORED);
            assertThat(storage.lifecycle(id).retentionExpiresAt()).isEqualTo("2026-12-01T00:00:00Z");
            assertThat(storage.get(purpose, id).size()).isEqualTo(4);
            assertThatThrownBy(() -> storage.extendRetention(id, OffsetDateTime.parse("2026-11-20T00:00:00Z")))
                    .isInstanceOf(ForgeException.class);
            retentionClock.at("2026-12-01T00:00:00Z");
            storage.expire(id);
            retentionClock.at("2026-12-08T00:00:00Z");
            assertThatThrownBy(() -> storage.extendRetention(id, OffsetDateTime.parse("2027-01-01T00:00:00Z")))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(StorageErrorCode.RETENTION_UNAVAILABLE));
            assertThatThrownBy(() -> storage.restore(purpose, id)).isInstanceOf(ForgeException.class);
            assertThat(storage.lifecycle(id).status()).isEqualTo(StorageObjectStatus.EXPIRED);
        }
    }

    @Test
    void unconfirmedUploadCanExpireAtItsUploadDeadlineAndRestoreStillNeedsConfirmation() {
        var purpose = new StoragePurpose("pending-document", Set.of("pdf"), 4, Duration.ofMinutes(2));
        try (var ignored = CurrentTenant.bind(tenant)) {
            var upload = storage.prepareUpload(purpose, "pending.pdf", null, 4);
            UUID id = upload.objectId();
            try (var client = s3Client()) {
                client.putObject(
                        request -> request.bucket(BUCKET).key(tenant + "/demo-service/" + id),
                        RequestBody.fromBytes(new byte[4]));
            }
            assertThat(storage.lifecycle(id).status()).isEqualTo(StorageObjectStatus.PENDING);
            retentionClock.current = upload.expiresAt().toInstant();
            assertThat(storage.usage().usedBytes()).isEqualTo(4);
            assertThat(storage.expire(id)).isTrue();
            assertThat(storage.lifecycle(id).status()).isEqualTo(StorageObjectStatus.EXPIRED);
            assertThat(storage.usage().usedBytes()).isEqualTo(4);
            assertThat(objectKeys()).contains(tenant + "/demo-service/" + id);
            assertThatThrownBy(() -> storage.completeUpload(id)).isInstanceOf(ForgeException.class);
            assertThat(storage.restore(purpose, id).status()).isEqualTo(StorageObjectStatus.PENDING);
            assertThatThrownBy(() -> storage.get(purpose, id)).isInstanceOf(ForgeException.class);
            assertThat(storage.completeUpload(id).size()).isEqualTo(4);
            assertThat(storage.get(purpose, id).size()).isEqualTo(4);
        }
    }

    @Test
    void purposeWithoutRetentionKeepsConfirmedFilesPermanently() {
        var purpose = new StoragePurpose("permanent-document", Set.of("pdf"), 4, Duration.ofMinutes(2));
        retentionClock.at("2026-11-01T00:00:00Z");
        try (var ignored = CurrentTenant.bind(tenant)) {
            UUID id = storage.write(purpose, "permanent.pdf", null, 4, new ByteArrayInputStream(new byte[4]))
                    .id();
            retentionClock.at("2036-11-01T00:00:00Z");
            assertThat(storage.expire(id)).isFalse();
            assertThat(storage.lifecycle(id).retentionExpiresAt()).isNull();
            assertThat(storage.get(purpose, id).size()).isEqualTo(4);
        }
    }

    @Test
    void demoHttpExportUsesItsCollectedManifestAndFailsWithoutAResultWhenARuleIsMissing() throws Exception {
        var manifest = ResourceManifestCollector.collect(
                "demo-service", "8", "io.saasforge.demo.masking", getClass().getClassLoader());
        Map<String, EffectiveFieldPolicy> policies = new LinkedHashMap<>();
        for (var field : manifest.sensitiveFields()) {
            policies.put(
                    field.path(),
                    new EffectiveFieldPolicy(
                            field.path(),
                            field.grade(),
                            new MaskingRule(field.defaultRule(), null, null),
                            !"L4".equals(field.grade())));
        }
        var json = JsonMapper.builder().build();
        TestExportMasking.policies(tenant, json.writeValueAsString(policies));
        UUID member = UUID.randomUUID();
        TestGrants.publish(redisTemplate, tenant, member, List.of("masking:read", "masking:export"));
        var online = exchangeAs(member, HttpMethod.GET, "/masking/profiles", null);
        assertThat(online.toString())
                .contains("138****5678", "110101********1234")
                .doesNotContain("13812345678", "110101199001011234");
        var submitted = exchangeAs(member, HttpMethod.POST, "/masking/profiles/export", null);
        assertThat(submitted.get("httpStatus")).isEqualTo(201);
        String id = (String) ((Map<?, ?>) submitted.get("data")).get("id");
        var task = awaitTask(member, id);
        assertThat(task.get("status")).isEqualTo("SUCCEEDED");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(downloadTaskFile(task, member)))) {
            var row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("138****5678");
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("110101********1234");
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("公开说明");
        }
        var field = policies.values().stream()
                .filter(policy -> "idcard".equals(policy.rule().strategy()))
                .findFirst()
                .orElseThrow();
        policies.put(field.path(), new EffectiveFieldPolicy(field.path(), field.grade(), null, true));
        TestExportMasking.policies(tenant, json.writeValueAsString(policies));
        var rejected = exchangeAs(member, HttpMethod.POST, "/masking/profiles/export", null);
        String failedId = (String) ((Map<?, ?>) rejected.get("data")).get("id");
        var failed = awaitTask(member, failedId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("errorCode")).isEqualTo("io.maskingFailed");
        assertThat(failed.get("resultAvailable")).isEqualTo(false);
        assertThat(exchangeAs(member, HttpMethod.GET, "/io/tasks/" + failedId + "/download", null)
                        .get("code"))
                .isEqualTo("io.resultUnavailable");
        List<String> audits = List.of();
        for (int attempt = 0; attempt < 100; attempt++) {
            audits = maskingAudit.payloads(tenant, "AuditEvent").stream()
                    .filter(payload -> payload.contains(failedId))
                    .toList();
            if (!audits.isEmpty()) {
                break;
            }
            Thread.sleep(10);
        }
        assertThat(audits)
                .singleElement()
                .satisfies(payload -> assertThat(payload)
                        .contains("export.run", "io.maskingFailed")
                        .doesNotContain("13812345678", "110101199001011234"));
    }

    @Autowired
    CurrentActorProvider actors;

    @Test
    void validImportWaitsForConfirmationWithoutWritingOrDeletingSource() throws Exception {
        String source = uploadWorkbook(new String[][] {{"待确认第一笔", "12.50", "线上"}, {"待确认第二笔", "20", "展会"}});
        String id = submitImport(source);
        var task = awaitValidation(id);
        assertThat(task.get("status")).isEqualTo("AWAITING_CONFIRM");
        assertThat(task.get("rowCount")).isEqualTo(2);
        assertThat(task.get("finishedAt")).isNull();
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .isEmpty();
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(storage.get(IoStoragePurposes.SOURCE, UUID.fromString(source))
                            .filename())
                    .isEqualTo("订单.xlsx");
        }
    }

    @Test
    void httpConfirmationWritesValidatedOrdersOnlyAfterSubmitterConfirms() throws Exception {
        String id = submitImport(uploadWorkbook(new String[][] {{"HTTP 确认订单", "12.50", "线上"}}));
        assertThat(awaitValidation(id).get("rowCount")).isEqualTo(1);
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .isEmpty();
        var confirmed = exchange(tenant, HttpMethod.POST, "/orders/import/" + id + "/confirm", null);
        assertThat(confirmed.get("httpStatus")).isEqualTo(200);
        assertThat(((Map<?, ?>) confirmed.get("data")).get("id")).isEqualTo(id);
        assertThat(awaitTask(id).get("status")).isEqualTo("SUCCEEDED");
        var orders = (List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data");
        assertThat(orders).hasSize(1);
        assertThat(((Map<?, ?>) orders.get(0)).get("title")).isEqualTo("HTTP 确认订单");
        assertThat(exchange(tenant, HttpMethod.POST, "/orders/import/" + id + "/confirm", null)
                        .get("code"))
                .isEqualTo("io.taskNotAwaitingConfirmation");
    }

    @Test
    void httpConfirmationRevalidatesChangedDictionaryAndProvidesNewReport() throws Exception {
        TestReferenceData.referral(tenant, "enabled");
        String id = submitImport(uploadWorkbook(new String[][] {{"依赖新字典值", "12.50", "转介绍"}}));
        assertThat(awaitValidation(id).get("status")).isEqualTo("AWAITING_CONFIRM");
        TestReferenceData.referral(tenant, "disabled");
        redisTemplate.opsForValue().increment(ReferenceDataKeys.version(tenant));
        assertThat(exchange(tenant, HttpMethod.POST, "/orders/import/" + id + "/confirm", null)
                        .get("httpStatus"))
                .isEqualTo(200);
        var failed = awaitTask(id);
        assertThat(failed.get("errorCode")).isEqualTo("io.validationFailed");
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .isEmpty();
        try (var report = new XSSFWorkbook(new ByteArrayInputStream(downloadTaskFile(failed)))) {
            assertThat(report.getSheetAt(0).getRow(1).getCell(3).getStringCellValue())
                    .contains("客户来源");
        }
    }

    @Test
    void httpConfirmationRequiresImportPermissionAndSubmitterWithinTenant() throws Exception {
        String id = submitImport(uploadWorkbook(new String[][] {{"提交人专属", "1", "线上"}}));
        assertThat(awaitValidation(id).get("status")).isEqualTo("AWAITING_CONFIRM");
        String path = "/orders/import/" + id + "/confirm";
        UUID otherMember = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, otherMember);
        var forbidden = exchangeAs(otherMember, HttpMethod.POST, path, null);
        assertThat(forbidden.get("httpStatus")).isEqualTo(403);
        assertThat(forbidden.get("code")).isEqualTo("io.confirmForbidden");
        UUID otherTenant = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, otherTenant, TestTokens.MEMBERSHIP_ID);
        var hidden = exchange(otherTenant, HttpMethod.POST, path, null);
        assertThat(hidden.get("httpStatus")).isEqualTo(404);
        assertThat(hidden.get("code")).isEqualTo("io.notFound");
        TestGrants.publish(redisTemplate, tenant, otherMember, List.of("order:read"));
        assertThat(exchangeAs(otherMember, HttpMethod.POST, path, null).get("httpStatus"))
                .isEqualTo(403);
        assertThat(awaitValidation(id).get("status")).isEqualTo("AWAITING_CONFIRM");
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .isEmpty();
    }

    @Autowired
    ConfigurableApplicationContext application;

    private AnnotationConfigApplicationContext anotherIoInstance() {
        return anotherIoInstance(Map.of());
    }

    private AnnotationConfigApplicationContext anotherIoInstance(Map<String, Object> overrides) {
        var instance = new AnnotationConfigApplicationContext();
        instance.setParent(application);
        var environment = new StandardEnvironment();
        environment.merge(application.getEnvironment());
        environment.getPropertySources().addFirst(new MapPropertySource("io-instance-overrides", overrides));
        instance.setEnvironment(environment);
        instance.register(IoAutoConfiguration.class);
        instance.refresh();
        return instance;
    }

    @Test
    void importCanBeConfirmedOnlyOnceAfterSubmittingInstanceStops() throws Exception {
        String source = uploadWorkbook(new String[][] {{"重启后确认", "12.50", "线上"}});
        try (var ignored = CurrentTenant.bind(tenant)) {
            storage.completeUpload(UUID.fromString(source));
        }
        UUID id;
        try (var submitting = anotherIoInstance()) {
            var task = asMember(() -> submitting
                    .getBean(ExcelIo.class)
                    .importFile(OrderImportRow.class, UUID.fromString(source), row -> List.of()));
            id = task.id();
            assertThat(awaitValidation(id.toString()).get("status")).isEqualTo("AWAITING_CONFIRM");
        }
        try (var confirming = anotherIoInstance()) {
            var start = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var other = confirming.getBean(ExcelIo.class);
            Function<ExcelIo, Object> confirm = client -> {
                try {
                    start.await(2, TimeUnit.SECONDS);
                    return asMember(() -> client.confirmImport(id, OrderImportRow.class, row -> List.of(), row -> {
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                        orderService.create(row.title(), row.amount(), row.customerSource());
                    }));
                } catch (ForgeException error) {
                    return error.errorCode().code();
                } catch (InterruptedException error) {
                    throw new IllegalStateException(error);
                }
            };
            var first = CompletableFuture.supplyAsync(() -> confirm.apply(excel));
            var second = CompletableFuture.supplyAsync(() -> confirm.apply(other));
            start.countDown();
            try {
                var outcomes = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
                assertThat(outcomes.stream().filter(result -> result instanceof IoTask))
                        .hasSize(1);
                assertThat(outcomes).contains("io.taskNotAwaitingConfirmation");
            } finally {
                release.countDown();
            }
            assertThat(awaitTask(id.toString()).get("status")).isEqualTo("SUCCEEDED");
        }
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .hasSize(1);
    }

    @Test
    void confirmationRevalidatesAllRowsAndPublishesANewReportWithoutWriting() throws Exception {
        String source = uploadWorkbook(new String[][] {{"本应有效", "1", "线上"}, {"业务已变化", "2", "线上"}});
        String id = submitImport(source);
        assertThat(awaitValidation(id).get("status")).isEqualTo("AWAITING_CONFIRM");
        asMember(() -> excel.confirmImport(
                UUID.fromString(id),
                OrderImportRow.class,
                row -> row.title().equals("业务已变化") ? List.of("确认前业务数据已变化") : List.of(),
                row -> orderService.create(row.title(), row.amount(), row.customerSource())));
        var failed = awaitTask(id);
        assertThat(failed.get("errorCode")).isEqualTo("io.validationFailed");
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .isEmpty();
        try (var report = new XSSFWorkbook(new ByteArrayInputStream(downloadTaskFile(failed)))) {
            assertThat(report.getSheetAt(0).getLastRowNum()).isEqualTo(2);
            assertThat(report.getSheetAt(0).getRow(2).getCell(3).getStringCellValue())
                    .contains("确认前业务数据已变化");
        }
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThatThrownBy(() -> storage.get(UUID.fromString(source))).isInstanceOf(ForgeException.class);
        }
    }

    @Test
    void onlySubmitterInTheSameTenantCanConfirm() throws Exception {
        String id = submitImport(uploadWorkbook(new String[][] {{"提交人专属", "1", "线上"}}));
        assertThat(awaitValidation(id).get("status")).isEqualTo("AWAITING_CONFIRM");
        UUID member = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, member);
        assertThatThrownBy(() -> asMember(
                        member,
                        1,
                        () -> excel.confirmImport(
                                UUID.fromString(id), OrderImportRow.class, row -> List.of(), row -> {})))
                .isInstanceOfSatisfying(
                        ForgeException.class,
                        error -> assertThat(error.errorCode().code()).isEqualTo("io.confirmForbidden"));
        UUID ownerTenant = tenant;
        tenant = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID);
        try {
            assertThatThrownBy(() -> asMember(() -> excel.confirmImport(
                            UUID.fromString(id), OrderImportRow.class, row -> List.of(), row -> {})))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo("io.notFound"));
        } finally {
            tenant = ownerTenant;
        }
        assertThat(awaitValidation(id).get("status")).isEqualTo("AWAITING_CONFIRM");
    }

    @Test
    void awaitingImportsDoNotOccupyQueueOrGetInterruptedAndBusyConfirmationCanBeRetried() throws Exception {
        String id = submitImport(uploadWorkbook(new String[][] {{"队列空闲后确认", "1", "线上"}}));
        var awaiting = awaitValidation(id);
        assertThat(awaiting.get("status")).isEqualTo("AWAITING_CONFIRM");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        IoTask first;
        IoTask second;
        try {
            first = asMember(() -> ioTasks.submit("EXPORT", null, "queue blocker", progress -> {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                return null;
            }));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            second = asMember(() -> ioTasks.submit("EXPORT", null, "queued", progress -> null));
            assertThatThrownBy(() -> asMember(() -> excel.confirmImport(
                            UUID.fromString(id), OrderImportRow.class, row -> List.of(), row -> {})))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo("io.busy"));
            Thread.sleep(1200);
            maintenance.interrupt();
            var stillAwaiting = awaitValidation(id);
            assertThat(stillAwaiting.get("status")).isEqualTo("AWAITING_CONFIRM");
            assertThat(stillAwaiting.get("heartbeatAt")).isEqualTo(awaiting.get("heartbeatAt"));
        } finally {
            release.countDown();
        }
        awaitTask(first.id().toString());
        awaitTask(second.id().toString());
        asMember(() -> excel.confirmImport(
                UUID.fromString(id),
                OrderImportRow.class,
                row -> List.of(),
                row -> orderService.create(row.title(), row.amount(), row.customerSource())));
        assertThat(awaitTask(id).get("status")).isEqualTo("SUCCEEDED");
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .hasSize(1);
    }

    @Test
    @Sql("/fixtures/io-confirmation-expiry.sql")
    void unconfirmedImportsExpireAfter24HoursAndRecoveryExpiresTheirSource() throws Exception {
        tenant = UUID.fromString("019c0110-0000-7000-8000-000000000099");
        TestGrants.publishAll(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID);
        UUID expired = UUID.fromString("019c0110-0000-7000-8000-000000000091");
        UUID recent = UUID.fromString("019c0110-0000-7000-8000-000000000092");
        UUID expiredSource = UUID.fromString("019c0110-0000-7000-8000-000000000081");
        UUID recentSource = UUID.fromString("019c0110-0000-7000-8000-000000000082");
        try (var client = s3Client()) {
            for (UUID source : List.of(expiredSource, recentSource)) {
                client.putObject(
                        request -> request.bucket(BUCKET).key(tenant + "/demo-service/" + source),
                        RequestBody.fromBytes(new byte[] {1}));
            }
        }
        assertThatThrownBy(() ->
                        asMember(() -> excel.confirmImport(expired, OrderImportRow.class, row -> List.of(), row -> {
                            throw new AssertionError("Expired source must not be imported");
                        })))
                .isInstanceOfSatisfying(
                        ForgeException.class,
                        error -> assertThat(error.errorCode().code()).isEqualTo("io.taskNotAwaitingConfirmation"));
        var registered =
                asMember(() -> ioTasks.submit("EXPORT", null, "register tenant for recovery", progress -> null));
        awaitTask(registered.id().toString());
        maintenance.interrupt();
        assertThat(asMember(() -> ioTasks.get(expired)).errorCode()).isEqualTo("io.confirmationExpired");
        assertThat(asMember(() -> ioTasks.get(expired)).status()).isEqualTo("FAILED");
        assertThat(asMember(() -> ioTasks.get(recent)).status()).isEqualTo("AWAITING_CONFIRM");
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThatThrownBy(() -> storage.get(expiredSource)).isInstanceOf(ForgeException.class);
            assertThat(storage.get(IoStoragePurposes.SOURCE, recentSource).filename())
                    .isEqualTo("recent.xlsx");
            assertThat(storage.lifecycle(expiredSource).status()).isEqualTo(StorageObjectStatus.EXPIRED);
            assertThat(storage.usage().usedBytes()).isEqualTo(2);
        }
        assertThat(objectKeys())
                .contains(tenant + "/demo-service/" + expiredSource)
                .contains(tenant + "/demo-service/" + recentSource);
    }

    @Test
    void stoppedInstanceDoesNotClaimConfirmationAndAnotherInstanceCanRetry() throws Exception {
        String id = submitImport(uploadWorkbook(new String[][] {{"换实例重试", "1", "线上"}}));
        var awaiting = awaitValidation(id);
        try (var stopped = anotherIoInstance()) {
            stopped.getBean(IoTasks.class).close();
            var client = stopped.getBean(ExcelIo.class);
            assertThatThrownBy(() -> asMember(() -> client.confirmImport(
                            UUID.fromString(id), OrderImportRow.class, row -> List.of(), row -> {})))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo("io.busy"));
        }
        var unchanged = awaitValidation(id);
        assertThat(unchanged.get("status")).isEqualTo("AWAITING_CONFIRM");
        assertThat(unchanged.get("heartbeatAt")).isEqualTo(awaiting.get("heartbeatAt"));
        assertThat(confirmImport(id).get("status")).isEqualTo("SUCCEEDED");
    }

    private Map<?, ?> awaitValidation(String id) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            var task = (Map<?, ?>)
                    exchange(tenant, HttpMethod.GET, "/io/tasks/" + id, null).get("data");
            if (!List.of("WAITING", "RUNNING").contains(task.get("status"))) {
                return task;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Validation did not complete: " + id);
    }

    @Test
    void importRejectsAttachmentObjectsWithoutCreatingTaskOrDeletingTheAttachment() throws Exception {
        String orderId = createOrder(tenant);
        byte[] content = "attachment".getBytes(StandardCharsets.UTF_8);
        var upload = prepareUpload(tenant, orderId, "attachment.xlsx", content.length);
        assertThat(put(upload, content)).isEqualTo(200);
        var attachment = (Map<?, ?>)
                exchange(tenant, HttpMethod.POST, attachments(orderId), Map.of("objectId", upload.get("objectId")))
                        .get("data");
        long before = asMember(ioTasks::count);
        var rejected = exchange(tenant, HttpMethod.POST, "/orders/import", Map.of("objectId", upload.get("objectId")));
        assertThat(rejected.get("code")).isEqualTo("storage.purposeMismatch");
        assertThat(rejected.get("userTip")).isEqualTo("文件用途不匹配，请选择正确的文件");
        assertThat(asMember(ioTasks::count)).isEqualTo(before);
        var download = (Map<?, ?>)
                exchange(tenant, HttpMethod.GET, attachments(orderId) + "/" + attachment.get("id") + "/download", null)
                        .get("data");
        assertThat(HTTP.send(
                                HttpRequest.newBuilder(URI.create((String) download.get("url")))
                                        .build(),
                                HttpResponse.BodyHandlers.ofByteArray())
                        .body())
                .isEqualTo(content);
    }

    @Test
    void anotherTenantsImportSourceCannotBeImportedOrAttached() throws Exception {
        UUID source;
        try (var ignored = CurrentTenant.bind(tenant)) {
            source = storage.write(
                            IoStoragePurposes.SOURCE, "source.xlsx", null, 1, new ByteArrayInputStream(new byte[] {1}))
                    .id();
        }
        UUID other = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, other, TestTokens.MEMBERSHIP_ID);
        var rejected = exchange(other, HttpMethod.POST, "/orders/import", Map.of("objectId", source));
        assertThat(rejected.get("code")).isEqualTo("storage.objectNotFound");
        assertThat(rejected.get("httpStatus")).isEqualTo(404);
        assertThat((List<?>) exchange(other, HttpMethod.GET, "/io/tasks", null).get("data"))
                .isEmpty();
        var attachment = exchange(other, HttpMethod.POST, attachments(createOrder(other)), Map.of("objectId", source));
        assertThat(attachment.get("code")).isEqualTo("storage.objectNotFound");
        assertThat(attachment.get("httpStatus")).isEqualTo(404);
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(storage.get(IoStoragePurposes.SOURCE, source).filename()).isEqualTo("source.xlsx");
        }
    }

    @Test
    void taskDownloadsRejectNonResultPurpose() throws Exception {
        UUID source;
        try (var ignored = CurrentTenant.bind(tenant)) {
            source = storage.write(
                            IoStoragePurposes.SOURCE, "source.xlsx", null, 1, new ByteArrayInputStream(new byte[] {1}))
                    .id();
        }
        var task = asMember(() -> ioTasks.submit("EXPORT", null, "custom result", progress -> source));
        assertThat(awaitTask(task.id().toString()).get("status")).isEqualTo("SUCCEEDED");
        var rejected = exchange(tenant, HttpMethod.GET, "/io/tasks/" + task.id() + "/download", null);
        assertThat(rejected.get("code")).isEqualTo("storage.purposeMismatch");
        assertThat(rejected.get("httpStatus")).isEqualTo(400);
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(storage.get(IoStoragePurposes.SOURCE, source).filename()).isEqualTo("source.xlsx");
        }
    }

    @Test
    void attachmentsRejectIoSourceAndResultObjectsBeforeRegistration() throws Exception {
        String orderId = createOrder(tenant);
        for (StoragePurpose purpose : List.of(IoStoragePurposes.SOURCE, IoStoragePurposes.RESULT)) {
            UUID id;
            try (var ignored = CurrentTenant.bind(tenant)) {
                id = storage.write(purpose, "io.xlsx", null, 1, new ByteArrayInputStream(new byte[] {1}))
                        .id();
            }
            var rejected = exchange(tenant, HttpMethod.POST, attachments(orderId), Map.of("objectId", id));
            assertThat(rejected.get("code")).isEqualTo("storage.purposeMismatch");
            assertThat(rejected.get("httpStatus")).isEqualTo(400);
            assertThat((List<?>) exchange(tenant, HttpMethod.GET, attachments(orderId), null)
                            .get("data"))
                    .isEmpty();
            try (var ignored = CurrentTenant.bind(tenant)) {
                assertThat(storage.get(purpose, id).filename()).isEqualTo("io.xlsx");
            }
        }
    }

    @Test
    void attachmentUploadsRejectDisallowedExtensionsAndOversizedFilesWithoutReservingQuota() throws Exception {
        String orderId = createOrder(tenant);
        var extension = exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("script.exe", 1));
        assertThat(extension.get("code")).isEqualTo("storage.extensionNotAllowed");
        assertThat(extension.get("httpStatus")).isEqualTo(400);
        var size =
                exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("large.pdf", 20L * 1024 * 1024 + 1));
        assertThat(size.get("code")).isEqualTo("storage.fileTooLarge");
        assertThat(size.get("httpStatus")).isEqualTo(400);
        assertThat(size.get("userTip")).isEqualTo("文件大小超过该用途的上限");
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, attachments(orderId), null)
                        .get("data"))
                .isEmpty();
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(storage.usage().usedBytes()).isZero();
        }
        for (String filename :
                List.of("document.txt", "document.pdf", "document.xlsx", "image.png", "image.jpg", "image.jpeg")) {
            assertThat(prepareUpload(tenant, orderId, filename, 1)).isNotEmpty();
        }
    }

    @Test
    void importRejectsResultObjectsBeforeCreatingTaskAndPreservesTheObject() throws Exception {
        UUID result;
        try (var ignored = CurrentTenant.bind(tenant)) {
            result = storage.write(
                            IoStoragePurposes.RESULT, "result.xlsx", null, 1, new ByteArrayInputStream(new byte[] {1}))
                    .id();
        }
        long before = asMember(ioTasks::count);
        var rejected = exchange(tenant, HttpMethod.POST, "/orders/import", Map.of("objectId", result));
        assertThat(rejected.get("code")).isEqualTo("storage.purposeMismatch");
        assertThat(rejected.get("httpStatus")).isEqualTo(400);
        assertThat(asMember(ioTasks::count)).isEqualTo(before);
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .isEmpty();
        try (var ignored = CurrentTenant.bind(tenant)) {
            var download = storage.prepareDownload(IoStoragePurposes.RESULT, result);
            assertThat(HTTP.send(
                                    HttpRequest.newBuilder(download.url()).build(),
                                    HttpResponse.BodyHandlers.ofByteArray())
                            .body())
                    .containsExactly(1);
        }
    }

    @Test
    void importUploadsRejectOtherExtensionsAndOversizedFilesWithoutReservingQuota() throws Exception {
        for (String filename : List.of("source.xls", "source.csv", "source.txt")) {
            var rejected = exchange(tenant, HttpMethod.POST, "/orders/import/upload", uploadRequest(filename, 1));
            assertThat(rejected.get("code")).isEqualTo("storage.extensionNotAllowed");
            assertThat(rejected.get("httpStatus")).isEqualTo(400);
            assertThat(rejected.get("userTip")).isEqualTo("该用途不支持此文件扩展名");
        }
        var oversized = exchange(
                tenant, HttpMethod.POST, "/orders/import/upload", uploadRequest("source.xlsx", 20L * 1024 * 1024 + 1));
        assertThat(oversized.get("code")).isEqualTo("storage.fileTooLarge");
        assertThat(oversized.get("httpStatus")).isEqualTo(400);
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(storage.usage().usedBytes()).isZero();
        }
        assertThat(exchange(tenant, HttpMethod.POST, "/orders/import/upload", uploadRequest("source.XLSX", 1))
                        .get("httpStatus"))
                .isEqualTo(201);
    }

    @Test
    void purposeRejectsDisallowedExtensionsBeforeReservingQuota() {
        var purpose = new StoragePurpose("test-document", Set.of("pdf"), 4, Duration.ofMinutes(2));
        try (var ignored = CurrentTenant.bind(tenant)) {
            for (String filename : List.of("document.exe", "document", "document.pdf.exe", "document.pdf.")) {
                assertThatThrownBy(() -> storage.prepareUpload(purpose, filename, null, 1))
                        .isInstanceOfSatisfying(
                                ForgeException.class,
                                error -> assertThat(error.errorCode().code()).isEqualTo("storage.extensionNotAllowed"));
            }
            assertThat(storage.usage().usedBytes()).isZero();
        }
    }

    @Test
    void purposeRejectsFilesAboveItsLimitBeforeReservingQuota() {
        var purpose = new StoragePurpose("test-document", Set.of("pdf"), 4, Duration.ofMinutes(2));
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThatThrownBy(() -> storage.prepareUpload(purpose, "document.PDF", null, 5))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo("storage.fileTooLarge"));
            assertThat(storage.usage().usedBytes()).isZero();
        }
    }

    @Test
    void uploadedPurposeControlsDownloadTtlAndDoesNotLeakAcrossTenants() throws Exception {
        var purpose = new StoragePurpose("test-document", Set.of("pdf"), 4, Duration.ofMinutes(2));
        var other = new StoragePurpose("test-other", Set.of("pdf"), 4, Duration.ofMinutes(9));
        UUID id;
        try (var ignored = CurrentTenant.bind(tenant)) {
            var upload = storage.prepareUpload(purpose, "合同.PDF", null, 4);
            id = upload.objectId();
            var request = HttpRequest.newBuilder(upload.url())
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[] {1, 2, 3, 4}));
            upload.headers().forEach(request::header);
            assertThat(HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding())
                            .statusCode())
                    .isEqualTo(200);
            storage.completeUpload(id);
            assertThat(storage.get(purpose, id).filename()).isEqualTo("合同.PDF");
            var download = storage.prepareDownload(purpose, id);
            assertThat(download.url().getQuery()).contains("X-Amz-Expires=120");
            assertThat(HTTP.send(
                                    HttpRequest.newBuilder(download.url()).build(),
                                    HttpResponse.BodyHandlers.ofByteArray())
                            .body())
                    .containsExactly(1, 2, 3, 4);
            assertThatThrownBy(() -> storage.get(other, id))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo("storage.purposeMismatch"));
            assertThatThrownBy(() -> storage.prepareDownload(other, id))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo("storage.purposeMismatch"));
        }
        try (var ignored = CurrentTenant.bind(UUID.randomUUID())) {
            for (StoragePurpose attempted : List.of(purpose, other)) {
                assertThatThrownBy(() -> storage.get(attempted, id))
                        .isInstanceOfSatisfying(
                                ForgeException.class,
                                error -> assertThat(error.errorCode()).isEqualTo(StorageErrorCode.OBJECT_NOT_FOUND));
                assertThatThrownBy(() -> storage.prepareDownload(attempted, id))
                        .isInstanceOfSatisfying(
                                ForgeException.class,
                                error -> assertThat(error.errorCode()).isEqualTo(StorageErrorCode.OBJECT_NOT_FOUND));
            }
        }
    }

    @Test
    void serverWriteAppliesPurposeRulesAndStoresThePurpose() throws Exception {
        var purpose = new StoragePurpose("test-document", Set.of("pdf"), 4, Duration.ofMinutes(2));
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThatThrownBy(() -> storage.write(
                            purpose, "document.exe", null, 4, new ByteArrayInputStream(new byte[] {1, 2, 3, 4})))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo("storage.extensionNotAllowed"));
            assertThatThrownBy(() -> storage.write(
                            purpose, "document.PDF", null, 5, new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5})))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo("storage.fileTooLarge"));
            assertThat(storage.usage().usedBytes()).isZero();
            var object =
                    storage.write(purpose, "document.PDF", null, 4, new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
            assertThat(storage.get(purpose, object.id())).isEqualTo(object);
            assertThat(storage.prepareDownload(purpose, object.id()).url().getQuery())
                    .contains("X-Amz-Expires=120");
            try (var content = storage.open(object.id())) {
                assertThat(content.readAllBytes()).containsExactly(1, 2, 3, 4);
            }
            assertThat(storage.usage().usedBytes()).isEqualTo(4);
        }
    }

    @Test
    void prePurposeObjectRemainsRegisteredButCannotBeDownloadedAsAnExplicitPurpose() {
        var purpose = new StoragePurpose("test-document", Set.of("pdf"), 4, Duration.ofMinutes(2));
        UUID legacyTenant = UUID.fromString("019b0110-0000-7000-8000-000000000099");
        UUID legacyObject = UUID.fromString("019b0110-0000-7000-8000-000000000098");
        try (var ignored = CurrentTenant.bind(legacyTenant)) {
            assertThat(storage.get(legacyObject).filename()).isEqualTo("legacy.pdf");
            assertThat(storage.lifecycle(legacyObject).status()).isEqualTo(StorageObjectStatus.STORED);
            assertThat(storage.lifecycle(legacyObject).retentionExpiresAt()).isNull();
            assertThat(storage.expire(legacyObject)).isFalse();
            UUID pending = UUID.fromString("019b0110-0000-7000-8000-000000000097");
            assertThat(storage.lifecycle(pending).status()).isEqualTo(StorageObjectStatus.PENDING);
            assertThat(storage.lifecycle(pending).retentionExpiresAt()).isNull();
            assertThat(storage.lifecycle(pending).expiredAt()).isNull();
            assertThatThrownBy(() -> storage.get(purpose, legacyObject))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo("storage.purposeMismatch"));
            assertThatThrownBy(() -> storage.prepareDownload(purpose, legacyObject))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo("storage.purposeMismatch"));
        }
    }

    @Test
    void backgroundTaskKeepsMembershipWhileStandardColumnsUseSystemActor() throws Exception {
        var task = asMember(() -> ioTasks.submit("EXPORT", null, "actor semantics", progress -> {
            assertThat(TenantContext.current().membershipId()).isEqualTo(TestTokens.MEMBERSHIP_ID);
            assertThat(actors.currentActor()).isEmpty();
            return null;
        }));
        assertThat(awaitTask(task.id().toString()).get("status")).isEqualTo("SUCCEEDED");
    }

    public record TypedImportRow(
            @ExcelColumn(value = "标题", order = 0, required = true)
            Integer title,

            @ExcelColumn(value = "金额", order = 1, required = true)
            LocalDate amount,

            @ExcelColumn(value = "客户来源", order = 2) String customerSource) {}

    @Test
    void integerOverflowAndInvalidDateBecomeRowErrors() throws Exception {
        String source =
                uploadWorkbook(new String[][] {{"1.5", "2026-99-99", ""}, {"99999999999999999", "2026-11-07", ""}});
        try (var ignored = CurrentTenant.bind(tenant)) {
            storage.completeUpload(UUID.fromString(source));
        }
        var task = asMember(() -> excel.importFile(TypedImportRow.class, UUID.fromString(source), row -> List.of()));
        assertThat(awaitTask(task.id().toString()).get("errorCode")).isEqualTo("io.validationFailed");
        assertThat(exchange(tenant, HttpMethod.GET, "/io/tasks/" + task.id() + "/download", null)
                        .get("code"))
                .isEqualTo("ok");
    }

    @Test
    void serverWriteFailureRollsBackRegistrationAndQuotaIsEnforced() throws Exception {
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThatThrownBy(() -> storage.write(
                            IoStoragePurposes.RESULT, "failed.xlsx", "application/octet-stream", 10, new InputStream() {
                                @Override
                                public int read() throws IOException {
                                    throw new IOException("test stream failure");
                                }
                            }))
                    .isInstanceOf(RuntimeException.class);
            assertThat(storage.usage().usedBytes()).isZero();
        }
        deliver(planChanged(0L, 1));
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThatThrownBy(() -> storage.write(
                            IoStoragePurposes.RESULT,
                            "quota.xlsx",
                            "application/octet-stream",
                            1,
                            new ByteArrayInputStream(new byte[] {1})))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(StorageErrorCode.QUOTA_EXCEEDED));
            assertThat(storage.usage().usedBytes()).isZero();
        }
    }

    @Test
    void queuedExportKeepsSubmissionPolicyWhenGrantStoreMovesToAnotherVersion() throws Exception {
        createOrder(tenant);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocker = asMember(() -> ioTasks.submit("EXPORT", null, "blocker", progress -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        String queued;
        try {
            queued = (String) ((Map<?, ?>) exchange(tenant, HttpMethod.POST, "/orders/export", Map.of())
                            .get("data"))
                    .get("id");
            TestGrants.publish(
                    redisTemplate,
                    tenant,
                    TestTokens.MEMBERSHIP_ID,
                    2,
                    TestGrants.ALL_ORDER_PERMISSIONS,
                    SubjectAttributes.NONE,
                    List.of());
            asMemberVersion(2, () -> dataPermission.currentSubject());
            redisTemplate.delete(MembershipStateKeys.grants(tenant, TestTokens.MEMBERSHIP_ID, 1));
        } finally {
            release.countDown();
        }
        assertThat(awaitTask(blocker.id().toString()).get("status")).isEqualTo("SUCCEEDED");
        assertThat(awaitTask(queued).get("status")).isEqualTo("SUCCEEDED");
        assertThat(awaitTask(queued).get("rowCount")).isEqualTo(1);
    }

    @Autowired
    DataPermission dataPermission;

    @Test
    void duplicateSelectableNamesAreReportedWithoutGuessing() throws Exception {
        TestReferenceData.conflict(tenant);
        TestReferenceData.referral(tenant, "enabled");
        var task = awaitTask(submitImport(uploadWorkbook(new String[][] {{"重名", "1", "线上"}})));
        assertThat(task.get("errorCode")).isEqualTo("io.validationFailed");
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .isEmpty();
    }

    public record MaskedExportRow(
            @ExcelColumn(value = "手机号", order = 1) @SensitiveField(path = "exportSample.phone", grade = "L3")
            String phone,

            @ExcelColumn(value = "备注", order = 2) String note) {}

    @Test
    void sensitiveExportMasksCellsAndLeavesUndeclaredColumnsUntouched() throws Exception {
        TestExportMasking.policies(tenant, """
                {"exportSample.phone":{"path":"exportSample.phone","grade":"L3","rule":{"strategy":"phone"},"exportAllowed":true}}
                """);
        var submitted = asMember(() -> excel.export(
                MaskedExportRow.class,
                "脱敏.xlsx",
                "phone=13812345678",
                rows -> rows.accept(new MaskedExportRow("13812345678", "ORD-UNCHANGED"))));
        var task = awaitTask(submitted.id().toString());
        assertThat(task.get("status")).isEqualTo("SUCCEEDED");
        List<String> audits = List.of();
        for (int i = 0; i < 100; i++) {
            audits = maskingAudit.payloads(tenant, "AuditEvent").stream()
                    .filter(payload -> payload.contains(submitted.id().toString()))
                    .toList();
            if (!audits.isEmpty()) {
                break;
            }
            Thread.sleep(10);
        }
        assertThat(audits)
                .singleElement()
                .satisfies(payload -> assertThat(payload).contains("export.run").doesNotContain("13812345678"));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(downloadTaskFile(task)))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("138****5678");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue())
                    .isEqualTo("ORD-UNCHANGED");
        }
    }

    @Test
    void sensitiveExportRejectsMissingRulesAndL4EvenWithZeroRows() throws Exception {
        for (String policy : List.of("{}", """
                {"exportSample.phone":{"path":"exportSample.phone","grade":"L3","exportAllowed":true}}
                """, """
                {"exportSample.phone":{"path":"exportSample.phone","grade":"L4","rule":{"strategy":"phone"},"exportAllowed":false}}
                """)) {
            TestExportMasking.policies(tenant, policy);
            var submitted = asMember(() -> excel.export(MaskedExportRow.class, "禁止.xlsx", "safe query", rows -> {}));
            var task = awaitTask(submitted.id().toString());
            assertThat(task.get("status")).isEqualTo("FAILED");
            assertThat(task.get("errorCode")).isEqualTo("io.maskingFailed");
            assertThat(task.get("resultObjectId")).isNull();
            assertThat(exchange(tenant, HttpMethod.GET, "/io/tasks/" + submitted.id() + "/download", null)
                            .get("code"))
                    .isEqualTo("io.resultUnavailable");
        }
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(storage.usage().usedBytes()).isZero();
        }
    }

    @Autowired
    OutboxAuditMapper maskingAudit;

    @Test
    void exportExecutionFailureProducesNoFileAndAuditsOnlySafeMetadata() throws Exception {
        TestExportMasking.policies(tenant, """
                {"exportSample.phone":{"path":"exportSample.phone","grade":"L3","rule":{"strategy":"keepFirst","keepFirst":99},"exportAllowed":true}}
                """);
        var submitted = asMember(() -> excel.export(
                MaskedExportRow.class,
                "失败.xlsx",
                "phone=13812345678",
                rows -> rows.accept(new MaskedExportRow("13812345678", "safe-note"))));
        var task = awaitTask(submitted.id().toString());
        assertThat(task.get("status")).isEqualTo("FAILED");
        assertThat(task.get("resultObjectId")).isNull();
        List<AuditEvent> events = List.of();
        for (int i = 0; i < 100; i++) {
            events = maskingAudit.payloads(tenant, "AuditEvent").stream()
                    .map(payload -> JsonMapper.builder().build().readValue(payload, AuditEvent.class))
                    .filter(event -> submitted.id().toString().equals(event.resourceId()))
                    .toList();
            if (!events.isEmpty()) {
                break;
            }
            Thread.sleep(10);
        }
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("export.run");
            assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
            assertThat(event.errorCode()).isEqualTo("io.maskingFailed");
            assertThat(JsonMapper.builder().build().writeValueAsString(event)).doesNotContain("13812345678");
        });
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(storage.usage().usedBytes()).isZero();
        }
    }

    @Test
    void exportUsesOneSnapshotAndNextTaskObservesConfigurationChanges() throws Exception {
        String phonePolicy = """
                {"exportSample.phone":{"path":"exportSample.phone","grade":"L3","rule":{"strategy":"phone"},"exportAllowed":true}}
                """;
        String replacement = """
                {"exportSample.phone":{"path":"exportSample.phone","grade":"L3","rule":{"strategy":"replace","replacement":"[changed]"},"exportAllowed":true}}
                """;
        TestExportMasking.policies(tenant, phonePolicy);
        var submitted = asMember(() -> excel.export(MaskedExportRow.class, "快照.xlsx", "safe query", rows -> {
            rows.accept(new MaskedExportRow("13812345678", "one"));
            TestExportMasking.policies(tenant, replacement);
            rows.accept(new MaskedExportRow("13912345678", "two"));
        }));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(
                downloadTaskFile(awaitTask(submitted.id().toString()))))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("138****5678");
            assertThat(workbook.getSheetAt(0).getRow(2).getCell(0).getStringCellValue())
                    .isEqualTo("139****5678");
        }
        submitted = asMember(() -> excel.export(
                MaskedExportRow.class,
                "新规则.xlsx",
                "safe query",
                rows -> rows.accept(new MaskedExportRow("13812345678", "one"))));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(
                downloadTaskFile(awaitTask(submitted.id().toString()))))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("[changed]");
        }
    }

    @Test
    void exportHonorsOwnDataScopeAndTranslatesDisabledNames() throws Exception {
        UUID own = UUID.randomUUID();
        TestGrants.publish(
                redisTemplate,
                tenant,
                own,
                1,
                TestGrants.ALL_ORDER_PERMISSIONS,
                SubjectAttributes.NONE,
                List.of(DataScope.SELF));
        exchangeAs(own, HttpMethod.POST, "/orders", Map.of("title", "本人的订单", "amount", 1));
        try (var ignored = CurrentTenant.bind(tenant)) {
            orderService.create("其他成员看不到", new BigDecimal("5"), "referral");
        }
        String id = (String) ((Map<?, ?>) exchangeAs(own, HttpMethod.POST, "/orders/export", Map.of())
                        .get("data"))
                .get("id");
        assertThat(awaitTask(own, id).get("rowCount")).isEqualTo(1);
        var ticket = (Map<?, ?>) exchangeAs(own, HttpMethod.GET, "/io/tasks/" + id + "/download", null)
                .get("data");
        byte[] file = HTTP.send(
                        HttpRequest.newBuilder(URI.create((String) ticket.get("url")))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .body();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
        }
        // 以管理员提交时，停用项也按名称导出。
        UUID admin = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, admin);
        var response = exchangeAs(admin, HttpMethod.POST, "/orders/export", Map.of());
        String all = (String) ((Map<?, ?>) response.get("data")).get("id");
        Map<?, ?> task = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            task = (Map<?, ?>)
                    exchangeAs(admin, HttpMethod.GET, "/io/tasks/" + all, null).get("data");
            if ("SUCCEEDED".equals(task.get("status"))) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(task.get("rowCount")).isEqualTo(2);
        ticket = (Map<?, ?>) exchangeAs(admin, HttpMethod.GET, "/io/tasks/" + all + "/download", null)
                .get("data");
        file = HTTP.send(
                        HttpRequest.newBuilder(URI.create((String) ticket.get("url")))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .body();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            var names = new ArrayList<String>();
            for (var row : workbook.getSheetAt(0)) {
                if (row.getRowNum() > 0) {
                    names.add(row.getCell(3).getStringCellValue());
                }
            }
            assertThat(names).contains("转介绍");
        }
        assertThat(exchangeAs(admin, HttpMethod.GET, "/io/tasks/" + id, null).get("httpStatus"))
                .isEqualTo(404);
        assertThat(exchangeAs(admin, HttpMethod.GET, "/io/tasks/" + id + "/download", null)
                        .get("httpStatus"))
                .isEqualTo(404);
    }

    @Test
    void exportFailsWhenQuotaOrRowLimitIsExceeded() throws Exception {
        for (int i = 0; i < 4; i++) {
            createOrder(tenant);
        }
        String id = (String) ((Map<?, ?>) exchange(tenant, HttpMethod.POST, "/orders/export", Map.of())
                        .get("data"))
                .get("id");
        assertThat(awaitTask(id).get("errorCode")).isEqualTo("io.rowLimit");
        tenant = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID);
        createOrder(tenant);
        deliver(planChanged(0L, 1));
        id = (String) ((Map<?, ?>) exchange(tenant, HttpMethod.POST, "/orders/export", Map.of())
                        .get("data"))
                .get("id");
        assertThat(awaitTask(id).get("errorCode")).isEqualTo("storage.quotaExceeded");
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(storage.usage().usedBytes()).isZero();
        }
    }

    @Test
    void expiredResultsEnterGraceAndKeepCapacityWhileTaskHistoryRemains() throws Exception {
        createOrder(tenant);
        String id = (String) ((Map<?, ?>) exchange(tenant, HttpMethod.POST, "/orders/export", Map.of())
                        .get("data"))
                .get("id");
        assertThat(awaitTask(id).get("status")).isEqualTo("SUCCEEDED");
        Thread.sleep(2200);
        UUID result = asMember(() -> ioTasks.get(UUID.fromString(id))).resultObjectId();
        try (var ignored = CurrentTenant.bind(tenant)) {
            long usedBytes = storage.usage().usedBytes();
            maintenance.expire();
            assertThat(storage.lifecycle(result).status()).isEqualTo(StorageObjectStatus.EXPIRED);
            assertThat(storage.usage().usedBytes()).isEqualTo(usedBytes).isPositive();
            assertThat(objectKeys()).contains(tenant + "/demo-service/" + result);
        }
        assertThat(exchange(tenant, HttpMethod.GET, "/io/tasks/" + id + "/download", null)
                        .get("code"))
                .isEqualTo("io.resultUnavailable");
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/io/tasks/" + id, null)
                                .get("data"))
                        .get("status"))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    @Sql("/fixtures/io-interrupted.sql")
    void interruptedInstanceTasksAreFailedByGlobalRecovery() throws Exception {
        tenant = UUID.fromString("019a0110-0000-7000-8000-000000000099");
        TestGrants.publishAll(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID);
        asMember(() -> ioTasks.submit("EXPORT", null, "register recovery", progress -> null));
        maintenance.interrupt();
        var task = (Map<?, ?>) exchange(tenant, HttpMethod.GET, "/io/tasks/019a0110-0000-7000-8000-000000000098", null)
                .get("data");
        assertThat(task.get("status")).isEqualTo("FAILED");
        assertThat(task.get("failureReason")).isEqualTo("执行中断，请重新提交");
    }

    @Test
    void busyPoolKeepsQueuedTaskAliveAndRejectsExcessSubmissions() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String first;
        String second;
        try {
            first = asMember(() -> ioTasks.submit("EXPORT", null, "test", progress -> {
                        started.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return null;
                    }))
                    .id()
                    .toString();
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            second = asMember(() -> ioTasks.submit("EXPORT", null, "test", progress -> null))
                    .id()
                    .toString();
            assertThatThrownBy(() -> asMember(() -> ioTasks.submit("EXPORT", null, "test", progress -> null)))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo("io.busy"));
            Thread.sleep(1200);
            maintenance.interrupt();
            assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/io/tasks/" + second, null)
                                    .get("data"))
                            .get("status"))
                    .isEqualTo("WAITING");
        } finally {
            release.countDown();
        }
        assertThat(awaitTask(first).get("status")).isEqualTo("SUCCEEDED");
        assertThat(awaitTask(second).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void importOverLimitAndWriteFailureLeaveNoBusinessRows() throws Exception {
        String source =
                uploadWorkbook(new String[][] {{"a", "1", "线上"}, {"b", "1", "线上"}, {"c", "1", "线上"}, {"d", "1", "线上"}});
        assertThat(awaitTask(submitImport(source)).get("errorCode")).isEqualTo("io.rowLimit");
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .isEmpty();
        String rollback = uploadWorkbook(new String[][] {{"先写入", "1", "线上"}, {"写入失败", "2", "线上"}});
        try (var ignored = CurrentTenant.bind(tenant)) {
            storage.completeUpload(UUID.fromString(rollback));
        }
        var task = asMember(() -> excel.importFile(OrderImportRow.class, UUID.fromString(rollback), row -> List.of()));
        assertThat(awaitValidation(task.id().toString()).get("status")).isEqualTo("AWAITING_CONFIRM");
        asMember(() -> excel.confirmImport(task.id(), OrderImportRow.class, row -> List.of(), row -> {
            if (row.title().equals("写入失败")) {
                throw new IllegalStateException("test rollback");
            }
            orderService.create(row.title(), row.amount(), row.customerSource());
        }));
        assertThat(awaitTask(task.id().toString()).get("status")).isEqualTo("FAILED");
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .isEmpty();
    }

    @Autowired
    OrderService orderService;

    private <T> T asMember(Supplier<T> action) {
        return asMemberVersion(1, action);
    }

    private <T> T asMemberVersion(long version, Supplier<T> action) {
        return asMember(TestTokens.MEMBERSHIP_ID, version, action);
    }

    private <T> T asMember(UUID membership, long version, Supplier<T> action) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var jwt = Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .subject(TestTokens.IDENTITY_ID.toString())
                .claim("tenant_id", tenant.toString())
                .claim("membership_id", membership.toString())
                .claim("ver", version)
                .build();
        context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        try (var ignored = CurrentTenant.bind(tenant)) {
            SecurityContextHolder.setContext(context);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    @Test
    void taskHistoryRemembersImportModelAndRejectsMismatchedConfirmation() throws Exception {
        String taskId = submitOverwrite(new String[][] {{"通过历史确认", "2", "线上", createOrder(tenant)}});
        assertThat(awaitValidation(taskId).get("importModel"))
                .isEqualTo("io.saasforge.demo.order.model.OrderOverwriteRow");
        var wrongModel = exchange(tenant, HttpMethod.POST, "/orders/import/" + taskId + "/confirm", null);
        assertThat(wrongModel.get("httpStatus")).isEqualTo(400);
        assertThat(wrongModel.get("code")).isEqualTo("io.invalidFile");
        assertThat(awaitValidation(taskId).get("status")).isEqualTo("AWAITING_CONFIRM");
        assertThat(exchange(tenant, HttpMethod.POST, "/orders/import/overwrite/" + taskId + "/confirm", null)
                        .get("httpStatus"))
                .isEqualTo(200);
        assertThat(awaitTask(taskId).get("status")).isEqualTo("SUCCEEDED");
        var history =
                (List<?>) exchange(tenant, HttpMethod.GET, "/io/tasks", null).get("data");
        assertThat(history).anySatisfy(item -> {
            var task = (Map<?, ?>) item;
            assertThat(task.get("id")).isEqualTo(taskId);
            assertThat(task.get("importModel")).isEqualTo("io.saasforge.demo.order.model.OrderOverwriteRow");
        });
    }

    @Test
    void globalMaintenanceClearsExpiredSnapshotsForSuspendedTenants() throws Exception {
        try (var instance = anotherIoInstance(Map.of("forge.io.backup-retention", "1ms"))) {
            IoTask task =
                    syntheticOverwrite(instance.getBean(ExcelIo.class), OrderImportRow.class, "suspended-expiry", "v1");
            redisTemplate.opsForValue().set(MembershipStateKeys.tenantStatus(tenant), "suspended");
            maintenance.interrupt();
            assertThat(asMember(() -> backups.list(task.id(), 100, 0))).isEmpty();
            assertThat(asMember(() -> ioTasks.get(task.id())).snapshotCount()).isEqualTo(1);
            assertThat(asMember(() -> rollbacks.preview(task.id(), OrderImportRow.class, id -> "v1"))
                            .unavailableReason())
                    .isEqualTo(ImportRollbackReason.SNAPSHOT_EXPIRED);
        }
    }

    @Test
    void expiryPreservesCommittedRecoveryCountsEvenWhenTheExecutionWasInterruptedBeforeItsSummary() throws Exception {
        String first = createOrder(tenant);
        String second = createOrder(tenant);
        String source = uploadWorkbook(
                new String[] {"标题 *", "金额 *", "客户来源", "订单ID"},
                new String[][] {{"覆盖一", "2", "线上", first}, {"覆盖二", "3", "线上", second}});
        try (var instance = anotherIoInstance(Map.of("forge.io.backup-retention", "3s"))) {
            var shortLived = new OrderIoService(orderService, instance.getBean(ExcelIo.class), storage, rollbacks);
            var submitted = asMember(() -> shortLived.importOverwrite(UUID.fromString(source)));
            awaitValidation(submitted.id().toString());
            asMember(() -> shortLived.confirmOverwrite(submitted.id()));
            awaitTask(submitted.id().toString());
            var task = asMember(() -> ioTasks.get(submitted.id()));
            var startedAt = Instant.now();
            var recoveries = new AtomicInteger();
            var restoredId = new AtomicReference<String>();
            var interruptedId = new AtomicReference<String>();
            assertThatThrownBy(() -> asMember(() ->
                            rollbacks.execute(task.id(), OrderOverwriteRow.class, false, rollbackWriter(backup -> {
                                restoreBackup(backup);
                                if (recoveries.incrementAndGet() == 2) {
                                    interruptedId.set(backup.recordId());
                                    throw new AssertionError("Interrupted before summary");
                                }
                                restoredId.set(backup.recordId());
                            }))))
                    .isInstanceOf(AssertionError.class);
            while (task.snapshotExpiresAt().isAfter(OffsetDateTime.now())) {
                Thread.sleep(25);
            }
            CurrentTenant.run(tenant, maintenance::expire);
            var history = asMember(() -> ioTasks.get(task.id()));
            assertThat(history.status()).isEqualTo("SUCCEEDED");
            assertThat(history.restoredCount()).isEqualTo(1);
            assertThat(history.rollbackStatus()).isEqualTo("PARTIAL");
            assertThat(history.snapshotCount()).isEqualTo(2);
            assertThat(asMember(() -> backups.list(task.id(), 100, 0))).isEmpty();
            assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + restoredId.get(), null)
                                    .get("data"))
                            .get("title"))
                    .isEqualTo("附件订单");
            assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + interruptedId.get(), null)
                                    .get("data"))
                            .get("title"))
                    .isIn("覆盖一", "覆盖二");
            assertThat(rollbackAuditEvents(task.id().toString()))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.tenantId()).isEqualTo(tenant);
                        assertThat(event.actorId()).isEqualTo(TestTokens.IDENTITY_ID);
                        assertThat(event.resourceId()).isEqualTo(task.id().toString());
                        assertThat(event.occurredAt()).isBetween(startedAt, Instant.now());
                        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
                        assertThat(event.detail())
                                .containsEntry("taskId", task.id().toString())
                                .containsEntry("restoredCount", 1)
                                .containsEntry("skippedCount", 0)
                                .containsEntry("failedCount", 0);
                    });
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThat(exchange(
                                        tenant,
                                        HttpMethod.POST,
                                        "/orders/import/" + task.id() + "/rollback",
                                        Map.of("skipConflicts", false))
                                .get("code"))
                        .isEqualTo("io.rollbackUnavailable");
            }
            var events = rollbackAuditEvents(task.id().toString());
            assertThat(events).hasSize(3);
            assertThat(events.stream()
                            .mapToLong(event -> ((Number) event.detail().get("restoredCount")).longValue())
                            .sum())
                    .isEqualTo(1);
            assertThat(events.subList(1, 3)).allSatisfy(event -> {
                assertThat(event.actorId()).isEqualTo(TestTokens.IDENTITY_ID);
                assertThat(event.resourceId()).isEqualTo(task.id().toString());
                assertThat(event.errorCode()).isEqualTo("io.rollbackUnavailable");
                assertThat(event.detail())
                        .containsEntry("restoredCount", 0)
                        .containsEntry("skippedCount", 0)
                        .containsEntry("failedCount", 0);
            });
        }
    }

    @Test
    void executionRechecksALaterImportThatCompletedAfterItsPreviewRead() throws Exception {
        String orderId = createOrder(tenant);
        String taskId = submitOverwrite(new String[][] {{"第一轮覆盖", "2", "线上", orderId}});
        confirmOverwrite(taskId);
        String laterId = submitOverwrite(new String[][] {{"后续覆盖", "5", "线上", orderId}});
        awaitValidation(laterId);
        var reads = new AtomicInteger();
        var result = asMember(() ->
                rollbacks.execute(UUID.fromString(taskId), OrderOverwriteRow.class, false, new ImportRollbackWriter() {
                    @Override
                    public String readVersion(String id) {
                        String version = orderService
                                .get(UUID.fromString(id))
                                .getUpdatedAt()
                                .toString();
                        if (reads.incrementAndGet() == 1) {
                            try {
                                confirmOverwrite(laterId);
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                        }
                        return version;
                    }

                    @Override
                    public String lockAndReadVersion(String id) {
                        return orderService.lockRollbackVersion(UUID.fromString(id));
                    }

                    @Override
                    public void restore(ImportBackup backup) {
                        throw new AssertionError("A later import must never be overwritten");
                    }
                }));
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.restoredCount()).isZero();
        assertThat(result.skippedCount()).isZero();
        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.conflicts().get(0).reason()).isEqualTo(ImportRollbackReason.LATER_IMPORT);
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + orderId, null)
                                .get("data"))
                        .get("title"))
                .isEqualTo("后续覆盖");
    }

    @Test
    void rollbackHttpExplicitlyRejectsExpiredSnapshotsAndTasksWithoutCoverage() throws Exception {
        try (var instance = anotherIoInstance(Map.of("forge.io.backup-retention", "1ms"))) {
            IoTask task =
                    syntheticOverwrite(instance.getBean(ExcelIo.class), OrderOverwriteRow.class, "expired-http", "v1");
            var response = exchange(
                    tenant,
                    HttpMethod.POST,
                    "/orders/import/" + task.id() + "/rollback",
                    Map.of("skipConflicts", true));
            assertThat(response.get("httpStatus")).isEqualTo(409);
            assertThat(response.get("code")).isEqualTo("io.rollbackUnavailable");
            var preview = (Map<?, ?>)
                    exchange(tenant, HttpMethod.GET, "/orders/import/" + task.id() + "/rollback-preview", null)
                            .get("data");
            assertThat(preview.get("unavailableReason")).isEqualTo("SNAPSHOT_EXPIRED");
        }
        String taskId = submitOverwrite(new String[][] {{"只新增", "2", "线上", ""}});
        confirmOverwrite(taskId);
        var response = exchange(
                tenant, HttpMethod.POST, "/orders/import/" + taskId + "/rollback", Map.of("skipConflicts", false));
        assertThat(response.get("httpStatus")).isEqualTo(409);
        assertThat(response.get("code")).isEqualTo("io.rollbackUnavailable");
    }

    @Test
    void failedRecoveryCanBeRetriedAndAlreadyRestoredRecordsAreNotWrittenAgain() throws Exception {
        String safe = createOrder(tenant);
        String failed = createOrder(tenant);
        String taskId = submitOverwrite(new String[][] {{"安全覆盖", "2", "线上", safe}, {"失败覆盖", "3", "线上", failed}});
        confirmOverwrite(taskId);
        var result = asMember(() ->
                rollbacks.execute(UUID.fromString(taskId), OrderOverwriteRow.class, false, rollbackWriter(backup -> {
                    restoreBackup(backup);
                    if (backup.recordId().equals(failed)) {
                        throw new IllegalStateException("Public business callback failure after writing");
                    }
                })));
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.restoredCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        var recovered = (Map<?, ?>)
                exchange(tenant, HttpMethod.GET, "/orders/" + safe, null).get("data");
        assertThat(recovered.get("title")).isEqualTo("附件订单");
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + failed, null)
                                .get("data"))
                        .get("title"))
                .isEqualTo("失败覆盖");
        var retry = (Map<?, ?>) exchange(
                        tenant,
                        HttpMethod.POST,
                        "/orders/import/" + taskId + "/rollback",
                        Map.of("skipConflicts", false))
                .get("data");
        assertThat(retry.get("status")).isEqualTo("SUCCEEDED");
        assertThat(retry.get("restoredCount")).isEqualTo(2);
        assertThat(retry.get("failedCount")).isEqualTo(0);
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + safe, null)
                                .get("data"))
                        .get("updatedAt"))
                .isEqualTo(recovered.get("updatedAt"));
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + failed, null)
                                .get("data"))
                        .get("title"))
                .isEqualTo("附件订单");
        var events = maskingAudit.payloads(tenant, "AuditEvent").stream()
                .map(payload -> JsonMapper.builder().build().readValue(payload, AuditEvent.class))
                .filter(event -> event.action().equals("io.importRollback"))
                .toList();
        assertThat(events).hasSize(3);
        assertThat(events.stream().map(AuditEvent::detail).toList())
                .containsExactlyInAnyOrder(
                        Map.of("taskId", taskId, "restoredCount", 1, "skippedCount", 0, "failedCount", 0),
                        Map.of("taskId", taskId, "restoredCount", 0, "skippedCount", 0, "failedCount", 1),
                        Map.of("taskId", taskId, "restoredCount", 1, "skippedCount", 0, "failedCount", 0));
        assertThat(events.stream().filter(event -> event.outcome() == AuditOutcome.FAILURE))
                .singleElement()
                .satisfies(event -> assertThat(event.errorCode()).isEqualTo("io.failed"));
    }

    @Test
    void interruptedExecutionReleasesItsLockAndRetryPreservesCommittedRecoveries() throws Exception {
        String taskId = submitOverwrite(
                new String[][] {{"覆盖一", "2", "线上", createOrder(tenant)}, {"覆盖二", "3", "线上", createOrder(tenant)}});
        confirmOverwrite(taskId);
        var startedAt = Instant.now();
        var steps = new AtomicInteger();
        var restoredId = new AtomicReference<String>();
        assertThatThrownBy(() -> asMember(() -> rollbacks.execute(
                        UUID.fromString(taskId), OrderOverwriteRow.class, false, rollbackWriter(backup -> {
                            if (steps.incrementAndGet() == 2) {
                                throw new AssertionError("Simulated process interruption");
                            }
                            restoreBackup(backup);
                            restoredId.set(backup.recordId());
                        }))))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Simulated process interruption");
        var interruptedEvents = rollbackAuditEvents(taskId);
        assertThat(interruptedEvents).singleElement().satisfies(event -> {
            assertThat(event.tenantId()).isEqualTo(tenant);
            assertThat(event.actorId()).isEqualTo(TestTokens.IDENTITY_ID);
            assertThat(event.resourceType()).isEqualTo("ioTask");
            assertThat(event.resourceId()).isEqualTo(taskId);
            assertThat(event.occurredAt()).isBetween(startedAt, Instant.now());
            assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(event.detail())
                    .containsEntry("taskId", taskId)
                    .containsEntry("restoredCount", 1)
                    .containsEntry("skippedCount", 0)
                    .containsEntry("failedCount", 0);
        });
        var beforeRetry = (Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + restoredId.get(), null)
                .get("data");
        var preview =
                (Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/import/" + taskId + "/rollback-preview", null)
                        .get("data");
        assertThat(preview.get("rollbackableCount")).isEqualTo(1);
        assertThat(preview.get("conflictCount")).isEqualTo(0);
        var retry = exchange(
                tenant, HttpMethod.POST, "/orders/import/" + taskId + "/rollback", Map.of("skipConflicts", false));
        assertThat(retry.get("httpStatus")).isEqualTo(200);
        assertThat(((Map<?, ?>) retry.get("data")).get("restoredCount")).isEqualTo(2);
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + restoredId.get(), null)
                                .get("data"))
                        .get("updatedAt"))
                .isEqualTo(beforeRetry.get("updatedAt"));
        assertThat(rollbackAuditEvents(taskId)).hasSize(2).allSatisfy(event -> {
            assertThat(event.actorId()).isEqualTo(TestTokens.IDENTITY_ID);
            assertThat(event.resourceId()).isEqualTo(taskId);
            assertThat(event.detail()).containsEntry("taskId", taskId).containsEntry("restoredCount", 1);
        });
        exchange(tenant, HttpMethod.POST, "/orders/import/" + taskId + "/rollback", Map.of("skipConflicts", false));
        var repeatedEvents = rollbackAuditEvents(taskId);
        assertThat(repeatedEvents).hasSize(3);
        assertThat(repeatedEvents.stream()
                        .mapToLong(event -> ((Number) event.detail().get("restoredCount")).longValue())
                        .sum())
                .isEqualTo(2);
        assertThat(repeatedEvents.get(2).detail())
                .containsEntry("restoredCount", 0)
                .containsEntry("skippedCount", 0)
                .containsEntry("failedCount", 0);
    }

    private List<AuditEvent> rollbackAuditEvents(String taskId) {
        return maskingAudit.payloads(tenant, "AuditEvent").stream()
                .map(payload -> JsonMapper.builder().build().readValue(payload, AuditEvent.class))
                .filter(event -> event.action().equals("io.importRollback") && taskId.equals(event.resourceId()))
                .toList();
    }

    @Test
    void simultaneousRollbackRequestsRestoreEachRecordOnceAndAuditOnlyActualWork() throws Exception {
        String orderId = createOrder(tenant);
        String taskId = submitOverwrite(new String[][] {{"并发覆盖", "2", "线上", orderId}});
        confirmOverwrite(taskId);
        var ready = new CountDownLatch(1);
        Supplier<Map<String, Object>> request = () -> {
            try {
                ready.await(5, TimeUnit.SECONDS);
                return exchange(
                        tenant,
                        HttpMethod.POST,
                        "/orders/import/" + taskId + "/rollback",
                        Map.of("skipConflicts", false));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        };
        var first = CompletableFuture.supplyAsync(request);
        var second = CompletableFuture.supplyAsync(request);
        ready.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).get("httpStatus")).isEqualTo(200);
        assertThat(second.get(10, TimeUnit.SECONDS).get("httpStatus")).isEqualTo(200);
        var task = (Map<?, ?>)
                exchange(tenant, HttpMethod.GET, "/io/tasks/" + taskId, null).get("data");
        assertThat(task.get("rollbackAttempts")).isEqualTo(2);
        assertThat(task.get("restoredCount")).isEqualTo(1);
        var events = maskingAudit.payloads(tenant, "AuditEvent").stream()
                .map(payload -> JsonMapper.builder().build().readValue(payload, AuditEvent.class))
                .filter(event -> event.action().equals("io.importRollback"))
                .toList();
        assertThat(events).hasSize(2);
        assertThat(events.stream()
                        .map(event -> ((Number) event.detail().get("restoredCount")).longValue())
                        .toList())
                .containsExactlyInAnyOrder(0L, 1L);
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + orderId, null)
                                .get("data"))
                        .get("title"))
                .isEqualTo("附件订单");
    }

    @Test
    void rollbackExecutionRequiresIndependentPermissionAndKeepsTenantOwnerAndCurrentDataBoundaries() throws Exception {
        String orderId = createOrder(tenant);
        String taskId = submitOverwrite(new String[][] {{"权限覆盖", "2", "线上", orderId}});
        confirmOverwrite(taskId);
        String path = "/orders/import/" + taskId + "/rollback";
        UUID importer = UUID.randomUUID();
        TestGrants.publish(redisTemplate, tenant, importer, List.of("order:import"));
        assertThat(exchangeAs(importer, HttpMethod.POST, path, Map.of("skipConflicts", true))
                        .get("httpStatus"))
                .isEqualTo(403);
        UUID other = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, other);
        assertThat(exchangeAs(other, HttpMethod.POST, path, Map.of("skipConflicts", true))
                        .get("httpStatus"))
                .isEqualTo(404);
        UUID otherTenant = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, otherTenant, TestTokens.MEMBERSHIP_ID);
        assertThat(exchange(otherTenant, HttpMethod.POST, path, Map.of("skipConflicts", true))
                        .get("httpStatus"))
                .isEqualTo(404);
        TestGrants.publish(
                redisTemplate,
                tenant,
                TestTokens.MEMBERSHIP_ID,
                2,
                List.of("order:importRollback"),
                SubjectAttributes.NONE,
                List.of());
        var restricted = RestClient.create("http://localhost:" + port)
                .post()
                .uri(path)
                .header("Authorization", "Bearer " + TestTokens.forMembership(tenant, TestTokens.MEMBERSHIP_ID, 2))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("skipConflicts", true))
                .retrieve()
                .body(Map.class);
        assertThat(restricted.get("httpStatus")).isEqualTo(200);
        var result = (Map<?, ?>) restricted.get("data");
        assertThat(result.get("restoredCount")).isEqualTo(0);
        assertThat(result.get("skippedCount")).isEqualTo(1);
        assertThat(result.get("conflicts"))
                .isEqualTo(List.of(Map.of("recordId", orderId, "reason", "RECORD_UNAVAILABLE")));
        assertThat(((Map<?, ?>) exchangeAs(other, HttpMethod.GET, "/orders/" + orderId, null)
                                .get("data"))
                        .get("title"))
                .isEqualTo("权限覆盖");
    }

    private ImportRollbackWriter rollbackWriter(Consumer<ImportBackup> restore) {
        return new ImportRollbackWriter() {
            @Override
            public String readVersion(String id) {
                return orderService.get(UUID.fromString(id)).getUpdatedAt().toString();
            }

            @Override
            public String lockAndReadVersion(String id) {
                return orderService
                        .getForImport(UUID.fromString(id))
                        .getUpdatedAt()
                        .toString();
            }

            @Override
            public void restore(ImportBackup backup) {
                restore.accept(backup);
            }
        };
    }

    private void restoreBackup(ImportBackup backup) {
        var values = backup.values();
        orderService.overwriteImported(
                UUID.fromString(backup.recordId()),
                (String) values.get("title"),
                new BigDecimal(values.get("amount").toString()),
                (String) values.get("customerSource"));
    }

    @Test
    void rollbackConflictsAreRejectedBeforeAnyRecoveryAndRequireExplicitSkip() throws Exception {
        String edited = createOrder(tenant);
        String safe = createOrder(tenant);
        String taskId = submitOverwrite(new String[][] {{"被改记录", "2", "线上", edited}, {"安全记录", "3", "线上", safe}});
        confirmOverwrite(taskId);
        exchange(tenant, HttpMethod.PUT, "/orders/" + edited, Map.of("title", "导入后的人工修改", "amount", 99));
        String path = "/orders/import/" + taskId + "/rollback";
        var blocked = exchange(tenant, HttpMethod.POST, path, Map.of("skipConflicts", false));
        assertThat(blocked.get("httpStatus")).isEqualTo(409);
        assertThat(blocked.get("code")).isEqualTo("io.rollbackConflicts");
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + safe, null)
                                .get("data"))
                        .get("title"))
                .isEqualTo("安全记录");
        var response = exchange(tenant, HttpMethod.POST, path, Map.of("skipConflicts", true));
        assertThat(response.get("httpStatus")).isEqualTo(200);
        var result = (Map<?, ?>) response.get("data");
        assertThat(result.get("status")).isEqualTo("PARTIAL");
        assertThat(result.get("restoredCount")).isEqualTo(1);
        assertThat(result.get("skippedCount")).isEqualTo(1);
        assertThat(result.get("failedCount")).isEqualTo(0);
        assertThat(result.get("conflicts")).isEqualTo(List.of(Map.of("recordId", edited, "reason", "RECORD_MODIFIED")));
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + edited, null)
                                .get("data"))
                        .get("title"))
                .isEqualTo("导入后的人工修改");
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + safe, null)
                                .get("data"))
                        .get("title"))
                .isEqualTo("附件订单");
        var events = maskingAudit.payloads(tenant, "AuditEvent").stream()
                .map(payload -> JsonMapper.builder().build().readValue(payload, AuditEvent.class))
                .filter(event -> event.action().equals("io.importRollback"))
                .toList();
        assertThat(events).hasSize(3);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.tenantId()).isEqualTo(tenant);
            assertThat(event.actorId()).isEqualTo(TestTokens.IDENTITY_ID);
            assertThat(event.resourceId()).isEqualTo(taskId);
            assertThat(event.detail().get("taskId")).isEqualTo(taskId);
            assertThat(event.detail().toString()).doesNotContain("附件订单", "人工修改", "values");
        });
        assertThat(events.get(0).detail())
                .containsEntry("restoredCount", 0)
                .containsEntry("skippedCount", 0)
                .containsEntry("failedCount", 0);
        assertThat(events.subList(1, 3).stream().map(AuditEvent::detail).toList())
                .containsExactlyInAnyOrder(
                        Map.of("taskId", taskId, "restoredCount", 1, "skippedCount", 0, "failedCount", 0),
                        Map.of("taskId", taskId, "restoredCount", 0, "skippedCount", 1, "failedCount", 0));
    }

    @Test
    void snapshotExpiryCannotCommitBetweenRecoveryAndItsSuccessMarker() throws Exception {
        String orderId = createOrder(tenant);
        String version = asMember(
                () -> orderService.get(UUID.fromString(orderId)).getUpdatedAt().toString());
        var cleanup = new AtomicReference<CompletableFuture<Void>>();
        try (var instance = anotherIoInstance(Map.of("forge.io.backup-retention", "2s"))) {
            IoTask task = syntheticOverwrite(instance.getBean(ExcelIo.class), OrderImportRow.class, orderId, version);
            var result = asMember(() ->
                    rollbacks.execute(task.id(), OrderImportRow.class, false, new ImportRollbackWriter() {
                        @Override
                        public String readVersion(String id) {
                            return orderService
                                    .get(UUID.fromString(id))
                                    .getUpdatedAt()
                                    .toString();
                        }

                        @Override
                        public String lockAndReadVersion(String id) {
                            return orderService
                                    .getForImport(UUID.fromString(id))
                                    .getUpdatedAt()
                                    .toString();
                        }

                        @Override
                        public void restore(ImportBackup backup) {
                            orderService.update(
                                    UUID.fromString(backup.recordId()), "已恢复且不能漏标", new BigDecimal("1"), null);
                            while (task.snapshotExpiresAt().isAfter(OffsetDateTime.now())) {
                                try {
                                    Thread.sleep(25);
                                } catch (InterruptedException error) {
                                    throw new IllegalStateException(error);
                                }
                            }
                            UUID currentTenant = tenant;
                            var ready = new CountDownLatch(1);
                            cleanup.set(CompletableFuture.runAsync(() -> CurrentTenant.run(currentTenant, () -> {
                                ready.countDown();
                                maintenance.expire();
                            })));
                            try {
                                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                                assertThatThrownBy(() -> cleanup.get().get(200, TimeUnit.MILLISECONDS))
                                        .isInstanceOf(TimeoutException.class);
                            } catch (InterruptedException error) {
                                throw new IllegalStateException(error);
                            }
                        }
                    }));
            cleanup.get().get(5, TimeUnit.SECONDS);
            assertThat(result.restoredCount()).isEqualTo(1);
            assertThat(result.failedCount()).isZero();
            assertThat(asMember(() -> backups.list(task.id(), 100, 0))).isEmpty();
            assertThat(asMember(() -> ioTasks.get(task.id())).restoredCount()).isEqualTo(1);
            var expired = asMember(
                    () -> rollbacks.execute(task.id(), OrderImportRow.class, false, new ImportRollbackWriter() {
                        @Override
                        public String readVersion(String id) {
                            throw new AssertionError("Expired snapshots must not read business data");
                        }

                        @Override
                        public String lockAndReadVersion(String id) {
                            throw new AssertionError("Expired snapshots must not lock business data");
                        }

                        @Override
                        public void restore(ImportBackup backup) {
                            throw new AssertionError("Expired snapshots must never restore");
                        }
                    }));
            assertThat(expired.status()).isEqualTo("UNAVAILABLE");
            assertThat(expired.unavailableReason()).isEqualTo(ImportRollbackReason.SNAPSHOT_EXPIRED);
            assertThat(expired.restoredCount()).isEqualTo(1);
            assertThat(asMember(() -> ioTasks.get(task.id())).rollbackStatus()).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void executionRechecksModificationAfterPreviewAndDoesNotImplicitlySkipWithoutConsent() throws Exception {
        String orderId = createOrder(tenant);
        String taskId = submitOverwrite(new String[][] {{"导入值", "12", "线上", orderId}});
        confirmOverwrite(taskId);
        var result = asMember(() ->
                rollbacks.execute(UUID.fromString(taskId), OrderOverwriteRow.class, false, new ImportRollbackWriter() {
                    @Override
                    public String readVersion(String id) {
                        return orderService
                                .get(UUID.fromString(id))
                                .getUpdatedAt()
                                .toString();
                    }

                    @Override
                    public String lockAndReadVersion(String id) {
                        orderService.update(UUID.fromString(id), "预览后的修改", new BigDecimal("99"), null);
                        return orderService
                                .getForImport(UUID.fromString(id))
                                .getUpdatedAt()
                                .toString();
                    }

                    @Override
                    public void restore(ImportBackup backup) {
                        throw new AssertionError("Conflict must never be overwritten");
                    }
                }));
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.restoredCount()).isZero();
        assertThat(result.skippedCount()).isZero();
        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.conflicts().get(0).reason()).isEqualTo(ImportRollbackReason.RECORD_MODIFIED);
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + orderId, null)
                                .get("data"))
                        .get("title"))
                .isEqualTo("预览后的修改");
    }

    @Test
    void expiredRollbackSnapshotsAreClearedWhileTheTaskKeepsItsDeadlineAndExplicitReason() throws Exception {
        try (var instance = anotherIoInstance(Map.of("forge.io.backup-retention", "1ms"))) {
            IoTask task =
                    syntheticOverwrite(instance.getBean(ExcelIo.class), OrderImportRow.class, "expired-cleanup", "v1");
            CurrentTenant.run(tenant, maintenance::expire);
            assertThat(asMember(() -> backups.list(task.id(), 100, 0))).isEmpty();
            var retained = asMember(() -> ioTasks.get(task.id()));
            assertThat(retained.status()).isEqualTo("SUCCEEDED");
            assertThat(retained.snapshotCount()).isEqualTo(1);
            assertThat(retained.snapshotExpiresAt()).isEqualTo(task.snapshotExpiresAt());
            assertThat(asMember(() -> rollbacks.preview(task.id(), OrderImportRow.class, id -> "v1"))
                            .unavailableReason())
                    .isEqualTo(ImportRollbackReason.SNAPSHOT_EXPIRED);
        }
    }

    @Test
    void rollbackRestoresOnlyCoveredFieldsAndKeepsNewRowsAndOriginalImportStatus() throws Exception {
        String orderId = createOrder(tenant);
        var before = (Map<?, ?>)
                exchange(tenant, HttpMethod.GET, "/orders/" + orderId, null).get("data");
        String taskId = submitOverwrite(new String[][] {{"覆盖后的标题", "12", "线上", orderId}, {"本次新增", "3", "线上", ""}});
        confirmOverwrite(taskId);
        String path = "/orders/import/" + taskId + "/rollback";
        var response = exchange(tenant, HttpMethod.POST, path, Map.of("skipConflicts", false));
        assertThat(response.get("httpStatus")).isEqualTo(200);
        var result = (Map<?, ?>) response.get("data");
        assertThat(result.get("status")).isEqualTo("SUCCEEDED");
        assertThat(result.get("restoredCount")).isEqualTo(1);
        assertThat(result.get("skippedCount")).isEqualTo(0);
        assertThat(result.get("failedCount")).isEqualTo(0);
        var restored = (Map<?, ?>)
                exchange(tenant, HttpMethod.GET, "/orders/" + orderId, null).get("data");
        assertThat(restored.get("title")).isEqualTo(before.get("title"));
        assertThat(restored.get("amount")).isEqualTo(before.get("amount"));
        assertThat(restored.get("customerSource")).isEqualTo(before.get("customerSource"));
        assertThat(restored.get("number")).isEqualTo(before.get("number"));
        assertThat(restored.get("status")).isEqualTo(before.get("status"));
        assertThat(restored.get("updatedBy")).isEqualTo(TestTokens.IDENTITY_ID.toString());
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .hasSize(2);
        var repeated = (Map<?, ?>) exchange(tenant, HttpMethod.POST, path, Map.of("skipConflicts", false))
                .get("data");
        assertThat(repeated.get("restoredCount")).isEqualTo(1);
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + orderId, null)
                                .get("data"))
                        .get("updatedAt"))
                .isEqualTo(restored.get("updatedAt"));
        var task = (Map<?, ?>)
                exchange(tenant, HttpMethod.GET, "/io/tasks/" + taskId, null).get("data");
        assertThat(task.get("status")).isEqualTo("SUCCEEDED");
        assertThat(task.get("rollbackStatus")).isEqualTo("SUCCEEDED");
        assertThat(task.get("rollbackAttempts")).isEqualTo(2);
        assertThat(task.get("restoredCount")).isEqualTo(1);
    }

    @Test
    void rollbackPreviewReportsOnlyCoveredRecordsAndDoesNotWriteBusinessData() throws Exception {
        String orderId = createOrder(tenant);
        String taskId = submitOverwrite(new String[][] {{"覆盖后的标题", "12", "线上", orderId}, {"本次新增", "3", "线上", ""}});
        confirmOverwrite(taskId);
        var response = exchange(tenant, HttpMethod.GET, "/orders/import/" + taskId + "/rollback-preview", null);
        assertThat(response.get("httpStatus")).isEqualTo(200);
        var report = (Map<?, ?>) response.get("data");
        assertThat(report.get("available")).isEqualTo(true);
        assertThat(report.get("blocked")).isEqualTo(false);
        assertThat(report.get("totalCount")).isEqualTo(1);
        assertThat(report.get("rollbackableCount")).isEqualTo(1);
        assertThat(report.get("conflictCount")).isEqualTo(0);
        assertThat((List<?>) report.get("conflicts")).isEmpty();
        assertThat(report.toString()).doesNotContain("附件订单", "覆盖后的标题", "fieldValues", "importedVersion");
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + orderId, null)
                                .get("data"))
                        .get("title"))
                .isEqualTo("覆盖后的标题");
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .hasSize(2);
    }

    @Test
    void rollbackPreviewUsesActualOverwriteOrderInsteadOfTaskCreationOrder() throws Exception {
        String orderId = createOrder(tenant);
        String earlierCreated = submitOverwrite(new String[][] {{"最后才覆盖", "30", "线上", orderId}});
        assertThat(awaitValidation(earlierCreated).get("status")).isEqualTo("AWAITING_CONFIRM");
        String laterCreated = submitOverwrite(new String[][] {{"先覆盖", "20", "线上", orderId}});
        confirmOverwrite(laterCreated);
        confirmOverwrite(earlierCreated);
        var report = (Map<?, ?>)
                exchange(tenant, HttpMethod.GET, "/orders/import/" + laterCreated + "/rollback-preview", null)
                        .get("data");
        assertThat(report.get("available")).isEqualTo(true);
        assertThat(report.get("blocked")).isEqualTo(true);
        assertThat(report.get("rollbackableCount")).isEqualTo(0);
        assertThat(report.get("conflicts")).isEqualTo(List.of(Map.of("recordId", orderId, "reason", "LATER_IMPORT")));
        var latest = (Map<?, ?>)
                exchange(tenant, HttpMethod.GET, "/orders/import/" + earlierCreated + "/rollback-preview", null)
                        .get("data");
        assertThat(latest.get("blocked")).isEqualTo(false);
        assertThat(latest.get("rollbackableCount")).isEqualTo(1);
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + orderId, null)
                                .get("data"))
                        .get("title"))
                .isEqualTo("最后才覆盖");
    }

    @Test
    void rollbackPreviewBlocksAfterManualEditAndCountsTheRemainingSafeRecord() throws Exception {
        String edited = createOrder(tenant);
        String safe = createOrder(tenant);
        String taskId = submitOverwrite(new String[][] {{"被改记录", "2", "线上", edited}, {"安全记录", "3", "线上", safe}});
        confirmOverwrite(taskId);
        assertThat(exchange(tenant, HttpMethod.PUT, "/orders/" + edited, Map.of("title", "导入后的人工修改", "amount", 99))
                        .get("httpStatus"))
                .isEqualTo(200);
        var report =
                (Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/import/" + taskId + "/rollback-preview", null)
                        .get("data");
        assertThat(report.get("blocked")).isEqualTo(true);
        assertThat(report.get("totalCount")).isEqualTo(2);
        assertThat(report.get("rollbackableCount")).isEqualTo(1);
        assertThat(report.get("conflictCount")).isEqualTo(1);
        assertThat(report.get("conflicts")).isEqualTo(List.of(Map.of("recordId", edited, "reason", "RECORD_MODIFIED")));
        assertThat(report.toString()).doesNotContain("导入后的人工修改", "附件订单", "安全记录");
        assertThat(((Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/" + edited, null)
                                .get("data"))
                        .get("title"))
                .isEqualTo("导入后的人工修改");
    }

    @Test
    void rollbackPreviewReportsDeletedRecordsWithoutExposingOldFields() throws Exception {
        String orderId = createOrder(tenant);
        String taskId = submitOverwrite(new String[][] {{"删除前覆盖", "2", "线上", orderId}});
        confirmOverwrite(taskId);
        assertThat(exchange(tenant, HttpMethod.DELETE, "/orders/" + orderId, null)
                        .get("httpStatus"))
                .isEqualTo(200);
        var report =
                (Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/import/" + taskId + "/rollback-preview", null)
                        .get("data");
        assertThat(report.get("blocked")).isEqualTo(true);
        assertThat(report.get("rollbackableCount")).isEqualTo(0);
        assertThat(report.get("conflicts"))
                .isEqualTo(List.of(Map.of("recordId", orderId, "reason", "RECORD_UNAVAILABLE")));
        assertThat(report.toString()).doesNotContain("附件订单", "删除前覆盖", "values");
    }

    @Test
    void rollbackPreviewUsesCurrentDataPolicyAndKeepsImportPermissionIndependent() throws Exception {
        String orderId = createOrder(tenant);
        String taskId = submitOverwrite(new String[][] {{"权限变化前覆盖", "2", "线上", orderId}});
        confirmOverwrite(taskId);
        String path = "/orders/import/" + taskId + "/rollback-preview";
        UUID importer = UUID.randomUUID();
        TestGrants.publish(redisTemplate, tenant, importer, List.of("order:import", "order:read"));
        assertThat(exchangeAs(importer, HttpMethod.GET, path, null).get("httpStatus"))
                .isEqualTo(403);
        UUID otherMember = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, otherMember);
        assertThat(exchangeAs(otherMember, HttpMethod.GET, path, null).get("httpStatus"))
                .isEqualTo(404);
        UUID otherTenant = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, otherTenant, TestTokens.MEMBERSHIP_ID);
        assertThat(exchange(otherTenant, HttpMethod.GET, path, null).get("httpStatus"))
                .isEqualTo(404);
        TestGrants.publish(
                redisTemplate,
                tenant,
                TestTokens.MEMBERSHIP_ID,
                2,
                TestGrants.ALL_ORDER_PERMISSIONS,
                SubjectAttributes.NONE,
                List.of());
        var response = RestClient.create("http://localhost:" + port)
                .get()
                .uri(path)
                .header("Authorization", "Bearer " + TestTokens.forMembership(tenant, TestTokens.MEMBERSHIP_ID, 2))
                .retrieve()
                .body(Map.class);
        assertThat(response.get("httpStatus")).isEqualTo(200);
        var report = (Map<?, ?>) response.get("data");
        assertThat(report.get("blocked")).isEqualTo(true);
        assertThat(report.get("conflicts"))
                .isEqualTo(List.of(Map.of("recordId", orderId, "reason", "RECORD_UNAVAILABLE")));
        assertThat(report.toString()).doesNotContain("附件订单", "权限变化前覆盖", "values");
    }

    @Test
    void rollbackPreviewExplicitlyRejectsTasksWithoutSnapshotsOrUnsupportedModels() throws Exception {
        String pending = submitOverwrite(new String[][] {{"待确认", "2", "线上", createOrder(tenant)}});
        awaitValidation(pending);
        var report =
                (Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/import/" + pending + "/rollback-preview", null)
                        .get("data");
        assertThat(report.get("available")).isEqualTo(false);
        assertThat(report.get("unavailableReason")).isEqualTo("TASK_NOT_SUCCEEDED");
        String onlyNew = submitOverwrite(new String[][] {{"无覆盖新增", "2", "线上", ""}});
        confirmOverwrite(onlyNew);
        report = (Map<?, ?>) exchange(tenant, HttpMethod.GET, "/orders/import/" + onlyNew + "/rollback-preview", null)
                .get("data");
        assertThat(report.get("available")).isEqualTo(false);
        assertThat(report.get("blocked")).isEqualTo(true);
        assertThat(report.get("unavailableReason")).isEqualTo("NO_SNAPSHOTS");
        var append = confirmImport(submitImport(uploadWorkbook(new String[][] {{"追加模型", "1", "线上"}})));
        report = (Map<?, ?>)
                exchange(tenant, HttpMethod.GET, "/orders/import/" + append.get("id") + "/rollback-preview", null)
                        .get("data");
        assertThat(report.get("available")).isEqualTo(false);
        assertThat(report.get("unavailableReason")).isEqualTo("MODEL_MISMATCH");
    }

    @Test
    void rollbackPreviewReportsExpiryInsteadOfAnEmptySuccessfulReport() throws Exception {
        try (var instance = anotherIoInstance(Map.of("forge.io.backup-retention", "1ms"))) {
            var shortLived = instance.getBean(ExcelIo.class);
            IoTask task = syntheticOverwrite(shortLived, OrderImportRow.class, "expiring-record", "version-1");
            var preview = asMember(() -> rollbacks.preview(task.id(), OrderImportRow.class, id -> "version-1"));
            assertThat(preview.available()).isFalse();
            assertThat(preview.blocked()).isTrue();
            assertThat(preview.unavailableReason()).isEqualTo(ImportRollbackReason.SNAPSHOT_EXPIRED);
            assertThat(preview.totalCount()).isEqualTo(1);
            assertThat(preview.rollbackableCount()).isZero();
            assertThat(preview.snapshotExpiresAt()).isBefore(OffsetDateTime.now());
        }
    }

    @Test
    void sdkRecheckUsesFreshCoverageStateAndSeparatesModelsWithTheSameRecordIdAndVersion() throws Exception {
        IoTask first = syntheticOverwrite(excel, OrderImportRow.class, "same-id", "unchanged-marker");
        var original = asMember(() -> backups.list(first.id(), 1, 0).get(0));
        assertThat(asMember(() -> rollbacks.check(original, id -> "modified-after-preview"))
                        .reason())
                .isEqualTo(ImportRollbackReason.RECORD_MODIFIED);
        UUID originalTenant = tenant;
        tenant = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID);
        syntheticOverwrite(excel, OrderImportRow.class, "same-id", "unchanged-marker");
        tenant = originalTenant;
        assertThat(asMember(() -> rollbacks.preview(first.id(), OrderImportRow.class, id -> "unchanged-marker"))
                        .blocked())
                .isFalse();
        syntheticOverwrite(excel, OtherRollbackRow.class, "same-id", "unchanged-marker");
        assertThat(asMember(() -> rollbacks.preview(first.id(), OrderImportRow.class, id -> "unchanged-marker"))
                        .blocked())
                .isFalse();
        syntheticOverwrite(excel, OrderImportRow.class, "same-id", "unchanged-marker");
        var conflict = asMember(() -> rollbacks.check(original, id -> "unchanged-marker"));
        assertThat(conflict.reason()).isEqualTo(ImportRollbackReason.LATER_IMPORT);
        assertThat(asMember(() -> rollbacks.preview(first.id(), OrderImportRow.class, id -> "unchanged-marker"))
                        .blocked())
                .isTrue();
    }

    public record OtherRollbackRow(
            @ExcelColumn(value = "标题", order = 1, required = true)
            String title,

            @ExcelColumn(value = "金额", order = 2, required = true)
            BigDecimal amount,

            @ExcelColumn(value = "客户来源", order = 3) String source) {}

    private <T> IoTask syntheticOverwrite(ExcelIo io, Class<T> model, String recordId, String version)
            throws Exception {
        String source = model == OrderOverwriteRow.class
                ? uploadWorkbook(
                        new String[] {"标题 *", "金额 *", "客户来源", "订单ID"}, new String[][] {{"SDK覆盖记录", "1", "线上", ""}})
                : uploadWorkbook(new String[][] {{"SDK覆盖记录", "1", "线上"}});

        asMember(() -> {
            storage.completeUpload(UUID.fromString(source));
            return null;
        });
        IoTask task = asMember(() -> io.importFile(model, UUID.fromString(source), row -> List.of()));
        assertThat(awaitValidation(task.id().toString()).get("status")).isEqualTo("AWAITING_CONFIRM");
        asMember(() -> io.confirmImport(task.id(), model, row -> List.of(), new ImportWriter<>() {
            @Override
            public ImportSnapshot beforeWrite(T row) {
                return new ImportSnapshot(recordId, Map.of("title", "不应暴露的覆盖前标题"));
            }

            @Override
            public String write(T row) {
                return version;
            }
        }));
        assertThat(awaitTask(task.id().toString()).get("status")).isEqualTo("SUCCEEDED");
        return asMember(() -> ioTasks.get(task.id()));
    }

    private String submitOverwrite(String[][] rows) throws Exception {
        String source = uploadWorkbook(new String[] {"标题 *", "金额 *", "客户来源", "订单ID"}, rows);
        var submitted = exchange(tenant, HttpMethod.POST, "/orders/import/overwrite", Map.of("objectId", source));
        assertThat(submitted.get("httpStatus")).isEqualTo(201);
        return (String) ((Map<?, ?>) submitted.get("data")).get("id");
    }

    @Test
    void appendAndPureNewOverwriteImportsHaveNoSnapshotOrExpiry() throws Exception {
        var appended = confirmImport(submitImport(uploadWorkbook(new String[][] {{"追加记录", "1", "线上"}})));
        assertThat(appended.get("snapshotAvailable")).isEqualTo(false);
        assertThat(appended.get("snapshotCount")).isEqualTo(0);
        assertThat(appended.get("snapshotExpiresAt")).isNull();
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(backups.list(UUID.fromString((String) appended.get("id")), 100, 0))
                    .isEmpty();
        }
        var created = confirmOverwrite(submitOverwrite(new String[][] {{"覆盖模板中的新增行", "3", "线上", ""}}));
        assertThat(created.get("snapshotAvailable")).isEqualTo(false);
        assertThat(created.get("snapshotCount")).isEqualTo(0);
        assertThat(created.get("snapshotExpiresAt")).isNull();
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(backups.list(UUID.fromString((String) created.get("id")), 100, 0))
                    .isEmpty();
        }
    }

    private Map<?, ?> confirmOverwrite(String taskId) throws Exception {
        assertThat(awaitValidation(taskId).get("status")).isEqualTo("AWAITING_CONFIRM");
        assertThat(exchange(tenant, HttpMethod.POST, "/orders/import/overwrite/" + taskId + "/confirm", null)
                        .get("httpStatus"))
                .isEqualTo(200);
        return awaitTask(taskId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenantDeletionRemovesItsSnapshotsAndAvailabilityWithoutAffectingOtherTenants() throws Exception {
        UUID originalTenant = tenant;
        String originalTask = submitOverwrite(new String[][] {{"原租户覆盖", "2", "线上", createOrder(tenant)}});
        assertThat(confirmOverwrite(originalTask).get("snapshotAvailable")).isEqualTo(true);
        UUID otherTenant = UUID.randomUUID();
        tenant = otherTenant;
        TestGrants.publishAll(redisTemplate, otherTenant, TestTokens.MEMBERSHIP_ID);
        assertThat(exchange(otherTenant, HttpMethod.GET, "/io/tasks/" + originalTask, null)
                        .get("httpStatus"))
                .isEqualTo(404);
        try (var ignored = CurrentTenant.bind(otherTenant)) {
            assertThat(backups.list(UUID.fromString(originalTask), 100, 0)).isEmpty();
        }
        String otherTask = submitOverwrite(new String[][] {{"其他租户覆盖", "3", "线上", createOrder(tenant)}});
        assertThat(confirmOverwrite(otherTask).get("snapshotAvailable")).isEqualTo(true);
        tenant = originalTenant;
        var cleaner = (EventHandler<TenantDeleted>) handlers.stream()
                .filter(handler -> handler.eventType() == TenantDeleted.class)
                .findFirst()
                .orElseThrow();
        redisTemplate.opsForValue().set(MembershipStateKeys.tenantStatus(originalTenant), "deleted");
        for (int delivery = 0; delivery < 2; delivery++) {
            CurrentTenant.run(
                    originalTenant,
                    () -> transaction.executeWithoutResult(
                            status -> cleaner.handle(new TenantDeleted(originalTenant))));
        }
        try (var ignored = CurrentTenant.bind(originalTenant)) {
            assertThat(backups.list(UUID.fromString(originalTask), 100, 0)).isEmpty();
        }
        assertThat(asMember(() -> ioTasks.get(UUID.fromString(originalTask))).snapshotAvailable())
                .isFalse();
        tenant = otherTenant;
        assertThat(asMember(() -> ioTasks.get(UUID.fromString(otherTask))).snapshotAvailable())
                .isTrue();
        try (var ignored = CurrentTenant.bind(otherTenant)) {
            assertThat(backups.list(UUID.fromString(otherTask), 100, 0)).hasSize(1);
            assertThat(backups.list(UUID.fromString(originalTask), 100, 0)).isEmpty();
        }
    }

    @Test
    void sdkSnapshotsPreserveExactDecimalValues() throws Exception {
        String source = uploadWorkbook(new String[][] {{"覆盖记录", "1", "线上"}});
        try (var ignored = CurrentTenant.bind(tenant)) {
            storage.completeUpload(UUID.fromString(source));
        }
        var submitted =
                asMember(() -> excel.importFile(OrderImportRow.class, UUID.fromString(source), row -> List.of()));
        assertThat(awaitValidation(submitted.id().toString()).get("status")).isEqualTo("AWAITING_CONFIRM");
        asMember(() ->
                excel.confirmImport(submitted.id(), OrderImportRow.class, row -> List.of(), new ImportWriter<>() {
                    @Override
                    public ImportSnapshot beforeWrite(OrderImportRow row) {
                        return new ImportSnapshot(
                                "external-record",
                                Map.of("balance", new BigDecimal("12345678901234567890.12345678901234567890")));
                    }

                    @Override
                    public String write(OrderImportRow row) {
                        return "version-2";
                    }
                }));
        assertThat(awaitTask(submitted.id().toString()).get("status")).isEqualTo("SUCCEEDED");
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(backups.list(submitted.id(), 100, 0))
                    .singleElement()
                    .satisfies(
                            backup -> assertThat(backup.values().get("balance").toString())
                                    .isEqualTo("12345678901234567890.12345678901234567890"));
        }
    }

    @Test
    void importRequiresAnObjectIdAndReturnsAFieldErrorBeforeAccessingStorage() throws Exception {
        Map<String, Object> explicitNull = new LinkedHashMap<>();
        explicitNull.put("objectId", null);
        for (String path : List.of("/orders/import/overwrite", "/orders/import")) {
            for (var body : List.of(Map.of(), explicitNull)) {
                var response = exchange(tenant, HttpMethod.POST, path, body);
                assertThat(response.get("httpStatus")).isEqualTo(400);
                assertThat(response.get("code")).isEqualTo("request.invalid");
                assertThat((List<?>) response.get("errors")).singleElement().satisfies(error -> {
                    assertThat(((Map<?, ?>) error).get("field")).isEqualTo("objectId");
                    assertThat(((Map<?, ?>) error).get("code")).isEqualTo("validation.notNull");
                });
            }
        }
    }

    @Test
    void overwriteImportMakesOnlyCoveredRecordsAvailableForRollbackAfterConfirmation() throws Exception {
        String orderId = createOrder(tenant);
        String source = uploadWorkbook(new String[] {"标题 *", "金额 *", "客户来源", "订单ID"}, new String[][] {
            {"覆盖后的订单", "25.50", "线上", orderId}, {"最后覆盖的订单", "30", "", orderId}, {"导入新增", "7", "线上", ""}
        });
        var submitted = exchange(tenant, HttpMethod.POST, "/orders/import/overwrite", Map.of("objectId", source));
        assertThat(submitted.get("httpStatus")).isEqualTo(201);
        String taskId = (String) ((Map<?, ?>) submitted.get("data")).get("id");
        var waiting = awaitValidation(taskId);
        assertThat(waiting.get("snapshotAvailable")).isEqualTo(false);
        assertThat(waiting.get("snapshotCount")).isEqualTo(0);
        var confirmed = exchange(tenant, HttpMethod.POST, "/orders/import/overwrite/" + taskId + "/confirm", null);
        assertThat(confirmed.get("httpStatus")).isEqualTo(200);
        var finished = awaitTask(taskId);
        assertThat(finished.get("status")).isEqualTo("SUCCEEDED");
        assertThat(finished.get("snapshotAvailable")).isEqualTo(true);
        assertThat(finished.get("snapshotCount")).isEqualTo(1);
        assertThat(java.time.OffsetDateTime.parse((String) finished.get("snapshotExpiresAt")))
                .isAfter(OffsetDateTime.now().plusDays(89));
        var order = (Map<?, ?>)
                exchange(tenant, HttpMethod.GET, "/orders/" + orderId, null).get("data");
        assertThat(order.get("title")).isEqualTo("最后覆盖的订单");
        assertThat(order.get("customerSource")).isNull();
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(backups.list(UUID.fromString(taskId), 100, 0))
                    .singleElement()
                    .satisfies(backup -> {
                        assertThat(backup.recordId()).isEqualTo(orderId);
                        assertThat(backup.values().keySet())
                                .containsExactlyInAnyOrder("title", "amount", "customerSource");
                        assertThat(backup.values())
                                .containsEntry("title", "附件订单")
                                .containsEntry("customerSource", null);
                        assertThat(new BigDecimal(backup.values().get("amount").toString()))
                                .isEqualByComparingTo("1");
                        assertThat(java.time.OffsetDateTime.parse(backup.importedVersion()))
                                .isEqualTo(java.time.OffsetDateTime.parse((String) order.get("updatedAt")));
                    });
            assertThat(backups.list(UUID.fromString(taskId), 1, 1)).isEmpty();
        }
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .hasSize(2);
    }

    @Test
    void writeFailureRollsBackCoveredValuesAndSnapshotsTogether() throws Exception {
        String firstId = createOrder(tenant);
        String secondId = createOrder(tenant);
        String taskId = submitOverwrite(new String[][] {{"本应回滚", "2", "线上", firstId}, {"让写入失败", "3", "线上", secondId}});
        assertThat(awaitValidation(taskId).get("status")).isEqualTo("AWAITING_CONFIRM");
        asMember(() -> excel.confirmImport(
                UUID.fromString(taskId), OrderOverwriteRow.class, row -> List.of(), new ImportWriter<>() {
                    @Override
                    public ImportSnapshot beforeWrite(OrderOverwriteRow row) {
                        var original = orderService.getForImport(row.id());
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("title", original.getTitle());
                        values.put("amount", original.getAmount());
                        values.put("customerSource", original.getCustomerSource());
                        return new ImportSnapshot(row.id().toString(), values);
                    }

                    @Override
                    public String write(OrderOverwriteRow row) {
                        if (row.title().equals("让写入失败")) {
                            throw new IllegalStateException("Expected business write failure");
                        }
                        return orderService
                                .overwriteImported(row.id(), row.title(), row.amount(), row.customerSource())
                                .getUpdatedAt()
                                .toString();
                    }
                }));
        var failed = awaitTask(taskId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("snapshotAvailable")).isEqualTo(false);
        assertThat(failed.get("snapshotCount")).isEqualTo(0);
        assertThat(failed.get("snapshotExpiresAt")).isNull();
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(backups.list(UUID.fromString(taskId), 100, 0)).isEmpty();
        }
        for (String id : List.of(firstId, secondId)) {
            var order = (Map<?, ?>)
                    exchange(tenant, HttpMethod.GET, "/orders/" + id, null).get("data");
            assertThat(order.get("title")).isEqualTo("附件订单");
            assertThat(new BigDecimal(order.get("amount").toString())).isEqualByComparingTo("1");
            assertThat(order.get("customerSource")).isNull();
        }
    }

    @Test
    void importMatchesNamesAndWritesAllRowsWhileInvalidNamesWriteNothing() throws Exception {
        String object = uploadWorkbook(new String[][] {{"第一笔", "12.50", "线上"}, {"第二笔", "20", "展会"}});
        var task = confirmImport(submitImport(object));
        assertThat(task.get("status")).isEqualTo("SUCCEEDED");
        var rows = (List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data");
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(row -> (String) ((Map<?, ?>) row).get("customerSource")))
                .containsExactlyInAnyOrder("online", "custom.fair");
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThatThrownBy(() -> storage.get(UUID.fromString(object))).isInstanceOf(ForgeException.class);
        }
        String invalid =
                uploadWorkbook(new String[][] {{"本不应写入", "1", "线上"}, {"无效编码", "2", "online"}, {"无效名称", "3", "不存在"}});
        var failed = awaitTask(submitImport(invalid));
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("errorCode")).isEqualTo("io.validationFailed");
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .hasSize(2);
        var download = (Map<?, ?>) exchange(tenant, HttpMethod.GET, "/io/tasks/" + failed.get("id") + "/download", null)
                .get("data");
        byte[] report = HTTP.send(
                        HttpRequest.newBuilder(URI.create((String) download.get("url")))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .body();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(report))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(3);
            assertThat(workbook.getSheetAt(0).getRow(2).getCell(3).getStringCellValue())
                    .contains("不是可选项名称");
        }
    }

    @Test
    void errorReportKeepsSelectableDictionaryDropdown() throws Exception {
        var task = awaitTask(submitImport(uploadWorkbook(new String[][] {{"测试订单", "123", "customer-source"}})));
        assertThat(task.get("errorCode")).isEqualTo("io.validationFailed");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(downloadTaskFile(task)))) {
            var validations = workbook.getSheetAt(0).getDataValidations();
            assertThat(validations).hasSize(1);
            var validation = validations.get(0);
            assertThat(validation.getRegions().getCellRangeAddresses()[0].isInRange(1, 2))
                    .isTrue();
            var name = workbook.getName(validation.getValidationConstraint().getFormula1());
            assertThat(name).isNotNull();
            var range = new AreaReference(name.getRefersToFormula(), SpreadsheetVersion.EXCEL2007);
            var choices = new ArrayList<String>();
            for (var cell : range.getAllReferencedCells()) {
                choices.add(workbook.getSheet(cell.getSheetName())
                        .getRow(cell.getRow())
                        .getCell(cell.getCol())
                        .getStringCellValue());
            }
            assertThat(choices).containsExactly("线上", "展会");
        }
    }

    @Test
    void errorReportExplainsDictionaryNamesAndWrapsLongText() throws Exception {
        var task = awaitTask(submitImport(uploadWorkbook(
                new String[][] {{"很长的测试订单标题".repeat(6), "123", "customer-source"}})));
        assertThat(task.get("errorCode")).isEqualTo("io.validationFailed");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(downloadTaskFile(task)))) {
            var sheet = workbook.getSheetAt(0);
            var row = sheet.getRow(1);
            var reason = row.getCell(3);
            assertThat(reason.getStringCellValue())
                    .contains("下拉列表", "名称", "线上", "展会", "字典编码", "选项编码")
                    .doesNotContain("转介绍");
            assertThat(sheet.getColumnWidth(3)).isGreaterThanOrEqualTo(60 * 256);
            assertThat(sheet.getColumnWidth(0)).isEqualTo(24 * 256);
            assertThat(sheet.getRow(0).getCell(3).getCellStyle().getWrapText()).isTrue();
            for (var cell : row) {
                assertThat(cell.getCellStyle().getWrapText()).isTrue();
                assertThat(cell.getCellStyle().getVerticalAlignment()).isEqualTo(VerticalAlignment.TOP);
            }
            assertThat(row.getHeightInPoints()).isGreaterThan(sheet.getDefaultRowHeightInPoints());
        }
    }

    private byte[] downloadTaskFile(Map<?, ?> task) throws Exception {
        return downloadTaskFile(task, TestTokens.MEMBERSHIP_ID);
    }

    private byte[] downloadTaskFile(Map<?, ?> task, UUID member) throws Exception {
        var ticket = (Map<?, ?>) exchangeAs(member, HttpMethod.GET, "/io/tasks/" + task.get("id") + "/download", null)
                .get("data");
        var file = HTTP.send(
                HttpRequest.newBuilder(URI.create((String) ticket.get("url"))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(file.statusCode()).isEqualTo(200);
        return file.body();
    }

    @Test
    void errorReportCanBeCorrectedAndImportedWithItsPreviousErrorsIntact() throws Exception {
        var failed = awaitTask(submitImport(
                uploadWorkbook(new String[][] {{"有效订单", "1", "线上"}, {"待修正订单", "123", "customer-source"}})));
        assertThat(failed.get("errorCode")).isEqualTo("io.validationFailed");
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .isEmpty();

        var stillInvalid = awaitTask(submitImport(uploadWorkbook(downloadTaskFile(failed))));
        assertThat(stillInvalid.get("errorCode")).isEqualTo("io.validationFailed");
        byte[] corrected;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(downloadTaskFile(stillInvalid)));
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 4);
            assertThat(sheet.getDataValidations()).hasSize(1);
            assertThat(sheet.getRow(2).getCell(3).getStringCellValue()).isNotBlank();
            // POI 修改 inlineStr 时会保留旧 is 节点，先清空以生成与 Excel 编辑结果一致的单一文本值。
            sheet.getRow(2).getCell(2).setBlank();
            sheet.getRow(2).getCell(2).setCellValue("展会");
            workbook.write(output);
            corrected = output.toByteArray();
        }

        var completed = confirmImport(submitImport(uploadWorkbook(corrected)));
        assertThat(completed.get("status")).isEqualTo("SUCCEEDED");
        assertThat(completed.get("rowCount")).isEqualTo(2);
        var rows = (List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data");
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(row -> (String) ((Map<?, ?>) row).get("customerSource")))
                .containsExactlyInAnyOrder("online", "custom.fair");
    }

    @Test
    void importOnlyIgnoresTrailingColumnNamedErrorReason() throws Exception {
        var unknownData = awaitTask(submitImport(uploadWorkbook(new String[][] {{"额外数据", "1", "线上", "备注"}})));
        assertThat(unknownData.get("errorCode")).isEqualTo("io.invalidFile");

        var failed = awaitTask(submitImport(uploadWorkbook(new String[][] {{"测试订单", "123", "customer-source"}})));
        byte[] unknownHeader;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(downloadTaskFile(failed)));
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            sheet.getRow(0).getCell(3).setBlank();
            sheet.getRow(0).getCell(3).setCellValue("备注");
            sheet.getRow(1).getCell(2).setBlank();
            sheet.getRow(1).getCell(2).setCellValue("线上");
            workbook.write(output);
            unknownHeader = output.toByteArray();
        }
        var rejected = awaitTask(submitImport(uploadWorkbook(unknownHeader)));
        assertThat(rejected.get("errorCode")).isEqualTo("io.invalidFile");
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, "/orders", null).get("data"))
                .isEmpty();
    }

    private String uploadWorkbook(String[][] rows) throws Exception {
        return uploadWorkbook(new String[] {"标题 *", "金额 *", "客户来源"}, rows);
    }

    private String uploadWorkbook(String[] headers, String[][] rows) throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("订单");
            var header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.createRow(i + 1);
                for (int c = 0; c < rows[i].length; c++) {
                    row.createCell(c).setCellValue(rows[i][c]);
                }
            }
            workbook.write(output);
            bytes = output.toByteArray();
        }
        return uploadWorkbook(bytes);
    }

    private String uploadWorkbook(byte[] bytes) throws Exception {
        var ticket = (Map<?, ?>) exchange(
                        tenant,
                        HttpMethod.POST,
                        "/orders/import/upload",
                        Map.of(
                                "filename",
                                "订单.xlsx",
                                "contentType",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "size",
                                bytes.length))
                .get("data");
        assertThat(put(ticket, bytes)).isEqualTo(200);
        return (String) ticket.get("objectId");
    }

    private String submitImport(String object) throws Exception {
        var response = exchange(tenant, HttpMethod.POST, "/orders/import", Map.of("objectId", object));
        assertThat(response.get("httpStatus")).isEqualTo(201);
        return (String) ((Map<?, ?>) response.get("data")).get("id");
    }

    private Map<?, ?> confirmImport(String id) throws Exception {
        assertThat(awaitValidation(id).get("status")).isEqualTo("AWAITING_CONFIRM");
        assertThat(exchange(tenant, HttpMethod.POST, "/orders/import/" + id + "/confirm", null)
                        .get("httpStatus"))
                .isEqualTo(200);
        return awaitTask(id);
    }

    private Map<?, ?> awaitTask(String id) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            var task = (Map<?, ?>)
                    exchange(tenant, HttpMethod.GET, "/io/tasks/" + id, null).get("data");
            if (List.of("SUCCEEDED", "FAILED").contains(task.get("status"))) {
                return task;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Task did not complete: " + id);
    }

    @Test
    void importTemplateCanBeDownloaded() throws Exception {
        var response = exchange(tenant, HttpMethod.GET, "/orders/import/template", null);
        assertThat(response.get("httpStatus")).isEqualTo(200);
        var data = (Map<?, ?>) response.get("data");
        byte[] bytes = Base64.getDecoder().decode((String) data.get("base64"));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("标题 *");
            assertThat(workbook.getSheetAt(0).getDataValidations()).hasSize(1);
            assertThat(workbook.getSheetAt(1).getRow(0).getCell(2).getStringCellValue())
                    .isEqualTo("线上");
        }
    }

    @Test
    void orderExportRunsAsynchronouslyAndOnlyItsSubmitterCanSeeTheResult() throws Exception {
        createOrder(tenant);
        var submitted = exchange(tenant, HttpMethod.POST, "/orders/export", Map.of());
        assertThat(submitted.get("httpStatus")).isEqualTo(201);
        String id = (String) ((Map<?, ?>) submitted.get("data")).get("id");
        Map<?, ?> task = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            task = (Map<?, ?>)
                    exchange(tenant, HttpMethod.GET, "/io/tasks/" + id, null).get("data");
            if (List.of("SUCCEEDED", "FAILED").contains(task.get("status"))) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(task.get("status")).isEqualTo("SUCCEEDED");
        assertThat(task.get("rowCount")).isEqualTo(1);
        var result = exchange(tenant, HttpMethod.GET, "/io/tasks/" + id + "/download", null);
        assertThat(result.get("code")).isEqualTo("ok");
        assertThat(((Map<?, ?>) result.get("data")).get("url").toString()).contains("X-Amz-Expires=300");
        var resultId = asMember(() -> ioTasks.get(UUID.fromString(id))).resultObjectId();
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(storage.get(IoStoragePurposes.RESULT, resultId).filename())
                    .isEqualTo("订单.xlsx");
        }
        UUID other = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, other, TestTokens.MEMBERSHIP_ID);
        assertThat(exchange(other, HttpMethod.GET, "/io/tasks/" + id, null).get("httpStatus"))
                .isEqualTo(404);
    }

    @Test
    void serverWrittenObjectCanBeStreamedAndDownloadedOnlyByItsTenant() throws Exception {
        byte[] content = "服务端文件".getBytes(StandardCharsets.UTF_8);
        UUID object;
        try (var ignored = CurrentTenant.bind(tenant)) {
            object = storage.write(
                            IoStoragePurposes.RESULT,
                            "导出.xlsx",
                            "application/octet-stream",
                            content.length,
                            new ByteArrayInputStream(content))
                    .id();
            try (var input = storage.open(object)) {
                assertThat(input.readAllBytes()).isEqualTo(content);
            }
            var ticket = storage.prepareDownload(IoStoragePurposes.RESULT, object);
            assertThat(HTTP.send(HttpRequest.newBuilder(ticket.url()).build(), HttpResponse.BodyHandlers.ofByteArray())
                            .body())
                    .isEqualTo(content);
        }
        try (var ignored = CurrentTenant.bind(UUID.randomUUID())) {
            assertThatThrownBy(() -> storage.open(object))
                    .isInstanceOfSatisfying(
                            ForgeException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(StorageErrorCode.OBJECT_NOT_FOUND));
        }
    }

    @Test
    void attachmentIsUploadedAndDownloadedThroughPresignedUrlsUnderTenantPrefix() throws Exception {
        String orderId = createOrder(tenant);
        byte[] content = "报价单内容".getBytes(StandardCharsets.UTF_8);
        Map<?, ?> ticket = prepareUpload(tenant, orderId, "报价单.txt", content.length);
        String objectId = (String) ticket.get("objectId");

        Map<String, Object> early =
                exchange(tenant, HttpMethod.POST, attachments(orderId), Map.of("objectId", objectId));
        assertThat(early.get("code")).isEqualTo("storage.uploadIncomplete");
        assertThat(early.get("httpStatus")).isEqualTo(409);

        // 申请时签入了大小，内容不一致的上传被存储拒绝
        assertThat(put(ticket, "多出来的内容".getBytes(StandardCharsets.UTF_8))).isEqualTo(403);
        assertThat(put(ticket, content)).isEqualTo(200);

        Map<String, Object> attached =
                exchange(tenant, HttpMethod.POST, attachments(orderId), Map.of("objectId", objectId));
        assertThat(attached.get("httpStatus")).isEqualTo(201);
        Map<?, ?> attachment = (Map<?, ?>) attached.get("data");
        assertThat(attachment.get("filename")).isEqualTo("报价单.txt");
        assertThat(attachment.get("contentType")).isEqualTo("text/plain");
        assertThat(attachment.get("size")).isEqualTo(content.length);
        assertThat(attachment.containsKey("objectId")).isFalse();

        assertThat((List<?>) exchange(tenant, HttpMethod.GET, attachments(orderId), null)
                        .get("data"))
                .singleElement()
                .isEqualTo(attachment);

        // 对象 key 由 SDK 生成，固定以 {tenant_id}/{service}/ 开头
        assertThat(objectKeys()).contains(tenant + "/demo-service/" + objectId);

        Map<?, ?> download = (Map<?, ?>)
                exchange(tenant, HttpMethod.GET, attachments(orderId) + "/" + attachment.get("id") + "/download", null)
                        .get("data");
        assertThat(download.get("url").toString()).contains("X-Amz-Expires=300");
        HttpResponse<byte[]> file = HTTP.send(
                HttpRequest.newBuilder(URI.create((String) download.get("url"))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(content);
        assertThat(file.headers().firstValue("Content-Disposition"))
                .hasValueSatisfying(value -> assertThat(value)
                        .startsWith("attachment;")
                        .contains("filename*=UTF-8''%E6%8A%A5%E4%BB%B7%E5%8D%95.txt"));
    }

    @Test
    void otherTenantsObjectsCannotBeAttachedDownloadedOrReachedByRewritingTheUrl() throws Exception {
        String orderId = createOrder(tenant);
        byte[] content = "机密".getBytes(StandardCharsets.UTF_8);
        Map<?, ?> ticket = prepareUpload(tenant, orderId, "机密.txt", content.length);
        put(ticket, content);
        String objectId = (String) ticket.get("objectId");
        Map<?, ?> attachment =
                (Map<?, ?>) exchange(tenant, HttpMethod.POST, attachments(orderId), Map.of("objectId", objectId))
                        .get("data");

        UUID other = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, other, TestTokens.MEMBERSHIP_ID);
        String otherOrderId = createOrder(other);

        Map<String, Object> stolen =
                exchange(other, HttpMethod.POST, attachments(otherOrderId), Map.of("objectId", objectId));
        assertThat(stolen.get("code")).isEqualTo("storage.objectNotFound");
        assertThat(stolen.get("httpStatus")).isEqualTo(404);

        Map<String, Object> foreignOrder =
                exchange(other, HttpMethod.GET, attachments(orderId) + "/" + attachment.get("id") + "/download", null);
        assertThat(foreignOrder.get("code")).isEqualTo("order.notFound");
        Map<String, Object> foreignAttachment = exchange(
                other, HttpMethod.GET, attachments(otherOrderId) + "/" + attachment.get("id") + "/download", null);
        assertThat(foreignAttachment.get("code")).isEqualTo("attachment.notFound");

        // 改写下载 URL 中的 key 指向其他租户的对象：签名不再匹配，存储拒绝
        Map<?, ?> otherTicket = prepareUpload(other, otherOrderId, "自己的.txt", 1);
        put(otherTicket, new byte[] {1});
        Map<?, ?> own = (Map<?, ?>) exchange(
                        other,
                        HttpMethod.POST,
                        attachments(otherOrderId),
                        Map.of("objectId", otherTicket.get("objectId")))
                .get("data");
        String ownUrl = (String) ((Map<?, ?>) exchange(
                                other,
                                HttpMethod.GET,
                                attachments(otherOrderId) + "/" + own.get("id") + "/download",
                                null)
                        .get("data"))
                .get("url");
        String rewritten = ownUrl.replace(
                other + "/demo-service/" + otherTicket.get("objectId"), tenant + "/demo-service/" + objectId);
        assertThat(rewritten).isNotEqualTo(ownUrl);
        HttpResponse<Void> denied = HTTP.send(
                HttpRequest.newBuilder(URI.create(rewritten)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(denied.statusCode()).isEqualTo(403);
    }

    @Test
    void uploadsBeyondPlanStorageQuotaAreRejectedUntilUpgraded() throws Exception {
        deliver(planChanged(1L, 1));
        String orderId = createOrder(tenant);
        int size = 600 * 1024;

        Map<?, ?> first = prepareUpload(tenant, orderId, "first.txt", size);
        // 未确认的上传申请同样占用容量，并发申请不会合计超出 Quota
        Map<String, Object> reserved =
                exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.txt", size));
        assertThat(reserved.get("code")).isEqualTo("storage.quotaExceeded");
        assertThat(reserved.get("httpStatus")).isEqualTo(409);
        assertThat(reserved.get("userTip")).isEqualTo("存储空间已达套餐上限，无法继续上传");

        put(first, new byte[size]);
        assertThat(exchange(tenant, HttpMethod.POST, attachments(orderId), Map.of("objectId", first.get("objectId")))
                        .get("httpStatus"))
                .isEqualTo(201);
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.txt", size))
                        .get("code"))
                .isEqualTo("storage.quotaExceeded");
        // 未超出时照常允许
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("small.txt", 1024))
                        .get("httpStatus"))
                .isEqualTo(201);

        // 重复或乱序送达的旧版本不生效
        deliver(planChanged(null, 1));
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.txt", size))
                        .get("code"))
                .isEqualTo("storage.quotaExceeded");

        deliver(planChanged(null, 2));
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.txt", size))
                        .get("httpStatus"))
                .isEqualTo(201);
    }

    @Test
    void uploadConfirmedAfterDowngradeBeyondQuotaIsRejectedWithoutPrematurePhysicalDeletion() throws Exception {
        String orderId = createOrder(tenant);
        int size = 600 * 1024;
        Map<?, ?> first = prepareUpload(tenant, orderId, "first.txt", size);
        Map<?, ?> second = prepareUpload(tenant, orderId, "second.txt", size);
        put(first, new byte[size]);
        put(second, new byte[size]);
        exchange(tenant, HttpMethod.POST, attachments(orderId), Map.of("objectId", first.get("objectId")));

        deliver(planChanged(1L, 1));
        Map<String, Object> rejected =
                exchange(tenant, HttpMethod.POST, attachments(orderId), Map.of("objectId", second.get("objectId")));
        assertThat(rejected.get("code")).isEqualTo("storage.quotaExceeded");
        assertThat(objectKeys()).contains(tenant + "/demo-service/" + second.get("objectId"));
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(storage.usage().usedBytes()).isEqualTo(2L * size);
            assertThatThrownBy(() -> storage.get(UUID.fromString((String) second.get("objectId"))))
                    .isInstanceOf(ForgeException.class);
        }
        // 降级前已确认的对象不受影响
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, attachments(orderId), null)
                        .get("data"))
                .hasSize(1);
    }

    @Test
    void fileReferenceChecksIncludeLogicallyDeletedAttachmentsWithoutCrossingTenantsOrPurposes() throws Exception {
        assertThat(fileReferenceChecks.batchSize()).isEqualTo(2);
        String orderId = createOrder(tenant);
        byte[] content = "retained-attachment".getBytes(StandardCharsets.UTF_8);
        Map<?, ?> ticket = prepareUpload(tenant, orderId, "reference.txt", content.length);
        assertThat(put(ticket, content)).isEqualTo(200);
        String objectId = (String) ticket.get("objectId");
        Map<String, Object> response =
                exchange(tenant, HttpMethod.POST, attachments(orderId), Map.of("objectId", objectId));
        assertThat(response.get("httpStatus")).isEqualTo(201);
        String attachmentId = (String) ((Map<?, ?>) response.get("data")).get("id");
        UUID referenced = UUID.fromString(objectId);
        UUID orphan = UUID.randomUUID();
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(fileReferenceChecks.unreferenced("order-attachment", List.of(referenced, orphan), "expire"))
                    .containsExactly(orphan);
            assertThat(fileReferenceChecks.unreferenced("io-result", List.of(orphan), "purge"))
                    .isEmpty();
        }
        assertThat(exchange(tenant, HttpMethod.DELETE, attachments(orderId) + "/" + attachmentId, null)
                        .get("httpStatus"))
                .isEqualTo(200);
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, attachments(orderId), null)
                        .get("data"))
                .isEmpty();
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(fileReferenceChecks.unreferenced("order-attachment", List.of(referenced, orphan), "purge"))
                    .containsExactly(orphan);
            assertThat(storage.lifecycle(referenced).status()).isEqualTo(StorageObjectStatus.EXPIRED);
        }
        try (var ignored = CurrentTenant.bind(UUID.randomUUID())) {
            assertThat(fileReferenceChecks.unreferenced("order-attachment", List.of(referenced), "purge"))
                    .containsExactly(referenced);
        }
        assertThat(objectKeys()).contains(tenant + "/demo-service/" + objectId);
    }

    @Test
    void deletingAttachmentStartsGracePeriodAndKeepsQuotaButOnlyWithinOwnTenant() throws Exception {
        deliver(planChanged(1L, 1));
        String orderId = createOrder(tenant);
        int size = 600 * 1024;
        Map<?, ?> ticket = prepareUpload(tenant, orderId, "first.txt", size);
        put(ticket, new byte[size]);
        String objectId = (String) ticket.get("objectId");
        String attachmentId = (String)
                ((Map<?, ?>) exchange(tenant, HttpMethod.POST, attachments(orderId), Map.of("objectId", objectId))
                                .get("data"))
                        .get("id");
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.txt", size))
                        .get("code"))
                .isEqualTo("storage.quotaExceeded");

        UUID other = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, other, TestTokens.MEMBERSHIP_ID);
        String otherOrderId = createOrder(other);
        assertThat(exchange(other, HttpMethod.DELETE, attachments(orderId) + "/" + attachmentId, null)
                        .get("code"))
                .isEqualTo("order.notFound");
        assertThat(exchange(other, HttpMethod.DELETE, attachments(otherOrderId) + "/" + attachmentId, null)
                        .get("code"))
                .isEqualTo("attachment.notFound");
        var stolen = new AtomicReference<Throwable>();
        CurrentTenant.run(other, () -> {
            try {
                storage.delete(UUID.fromString(objectId));
            } catch (ForgeException e) {
                stolen.set(e);
            }
        });
        assertThat(stolen.get())
                .isInstanceOfSatisfying(
                        ForgeException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(StorageErrorCode.OBJECT_NOT_FOUND));
        assertThat(objectKeys()).contains(tenant + "/demo-service/" + objectId);

        Map<String, Object> deleted =
                exchange(tenant, HttpMethod.DELETE, attachments(orderId) + "/" + attachmentId, null);
        assertThat(deleted.get("httpStatus")).isEqualTo(200);
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, attachments(orderId), null)
                        .get("data"))
                .isEmpty();
        assertThat(objectKeys()).contains(tenant + "/demo-service/" + objectId);
        try (var ignored = CurrentTenant.bind(tenant)) {
            assertThat(storage.lifecycle(UUID.fromString(objectId)).status()).isEqualTo(StorageObjectStatus.EXPIRED);
            assertThat(storage.usage().usedBytes()).isEqualTo(size);
        }
        assertThat(exchange(tenant, HttpMethod.GET, attachments(orderId) + "/" + attachmentId + "/download", null)
                        .get("code"))
                .isEqualTo("attachment.notFound");
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.txt", size))
                        .get("code"))
                .isEqualTo("storage.quotaExceeded");
    }

    private TenantPlanChanged planChanged(Long storageQuotaMb, long version) {
        return new TenantPlanChanged(
                tenant, UUID.randomUUID(), "test", List.of(), List.of(), null, storageQuotaMb, version);
    }

    /** 与 SDK 消费端一致：先恢复发布方的租户，再在事务中调用处理器。 */
    @SuppressWarnings("unchecked")
    private void deliver(TenantPlanChanged event) {
        var handler = (EventHandler<TenantPlanChanged>) handlers.stream()
                .filter(candidate -> candidate.eventType() == TenantPlanChanged.class)
                .findFirst()
                .orElseThrow();
        CurrentTenant.run(tenant, () -> transaction.executeWithoutResult(status -> handler.handle(event)));
    }

    private String createOrder(UUID tenantId) throws Exception {
        Map<?, ?> order =
                (Map<?, ?>) exchange(tenantId, HttpMethod.POST, "/orders", Map.of("title", "附件订单", "amount", 1))
                        .get("data");
        return (String) order.get("id");
    }

    private Map<?, ?> prepareUpload(UUID tenantId, String orderId, String filename, long size) throws Exception {
        Map<String, Object> envelope =
                exchange(tenantId, HttpMethod.POST, uploads(orderId), uploadRequest(filename, size));
        assertThat(envelope.get("httpStatus")).isEqualTo(201);
        return (Map<?, ?>) envelope.get("data");
    }

    private static Map<String, Object> uploadRequest(String filename, long size) {
        return Map.of("filename", filename, "contentType", "text/plain", "size", size);
    }

    /** 按凭证直传存储，返回存储的 HTTP 状态码。 */
    private static int put(Map<?, ?> ticket, byte[] content) throws Exception {
        var request = HttpRequest.newBuilder(URI.create((String) ticket.get("url")))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content));
        ((Map<?, ?>) ticket.get("headers")).forEach((name, value) -> request.header((String) name, (String) value));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static String uploads(String orderId) {
        return attachments(orderId) + "/uploads";
    }

    private static String attachments(String orderId) {
        return "/orders/" + orderId + "/attachments";
    }

    private static List<String> objectKeys() {
        try (S3Client client = s3Client()) {
            return client.listObjectsV2Paginator(request -> request.bucket(BUCKET)).contents().stream()
                    .map(S3Object::key)
                    .toList();
        }
    }

    private static String endpoint() {
        return "http://" + s3.getHost() + ":" + s3.getMappedPort(9000);
    }

    private static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .build();
    }

    /** 返回信封本身，并断言 HTTP 状态码与信封的 httpStatus 一致。 */
    private Map<String, Object> exchange(UUID tenantId, HttpMethod method, String uri, Object body) throws Exception {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + TestTokens.forTenant(tenantId));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((req, response) -> {
            Map<String, Object> envelope = response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(envelope).isNotNull();
            assertThat(envelope.get("httpStatus"))
                    .isEqualTo(response.getStatusCode().value());
            return envelope;
        });
    }

    private Map<String, Object> exchangeAs(UUID member, HttpMethod method, String path, Object body) throws Exception {
        var request = RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(path)
                .header("Authorization", "Bearer " + TestTokens.forMembership(tenant, member));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange(
                (req, response) -> response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {}));
    }

    private Map<?, ?> awaitTask(UUID member, String id) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            var task = (Map<?, ?>)
                    exchangeAs(member, HttpMethod.GET, "/io/tasks/" + id, null).get("data");
            if (List.of("SUCCEEDED", "FAILED").contains(task.get("status"))) {
                return task;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Task did not finish");
    }
}
