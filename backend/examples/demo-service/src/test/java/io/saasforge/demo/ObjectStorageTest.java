package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.demo.category.mapper.OutboxAuditMapper;
import io.saasforge.demo.order.model.OrderImportRow;
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
import io.saasforge.sdk.io.IoAutoConfiguration;
import io.saasforge.sdk.io.IoMaintenance;
import io.saasforge.sdk.io.IoStoragePurposes;
import io.saasforge.sdk.io.IoTask;
import io.saasforge.sdk.io.IoTasks;
import io.saasforge.sdk.manifest.ResourceManifestCollector;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import io.saasforge.sdk.security.EffectiveFieldPolicy;
import io.saasforge.sdk.security.MaskingRule;
import io.saasforge.sdk.security.SensitiveField;
import io.saasforge.sdk.security.TenantContext;
import io.saasforge.sdk.storage.ObjectStorage;
import io.saasforge.sdk.storage.StorageErrorCode;
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
import java.time.Duration;
import java.time.LocalDate;
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
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
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
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:storage-purpose-migration",
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
    IoTasks ioTasks;

    @Autowired
    ExcelIo excel;

    @Autowired
    IoMaintenance maintenance;

    private UUID tenant;

    @BeforeEach
    void newTenant() {
        tenant = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID);
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
        var instance = new AnnotationConfigApplicationContext();
        instance.setParent(application);
        instance.setEnvironment(application.getEnvironment());
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
    void unconfirmedImportsExpireAfter24HoursAndRecoveryDeletesTheirSource() throws Exception {
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
            assertThat(storage.usage().usedBytes()).isEqualTo(1);
        }
        assertThat(objectKeys())
                .doesNotContain(tenant + "/demo-service/" + expiredSource)
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
    void expiredResultsAreDeletedButTaskHistoryRemains() throws Exception {
        createOrder(tenant);
        String id = (String) ((Map<?, ?>) exchange(tenant, HttpMethod.POST, "/orders/export", Map.of())
                        .get("data"))
                .get("id");
        assertThat(awaitTask(id).get("status")).isEqualTo("SUCCEEDED");
        Thread.sleep(2200);
        try (var ignored = CurrentTenant.bind(tenant)) {
            maintenance.expire();
            assertThat(storage.usage().usedBytes()).isZero();
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
        byte[] bytes;
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("订单");
            var header = sheet.createRow(0);
            String[] headers = {"标题 *", "金额 *", "客户来源"};
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
    void uploadConfirmedAfterDowngradeBeyondQuotaIsRejectedAndRemoved() throws Exception {
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
        assertThat(objectKeys()).doesNotContain(tenant + "/demo-service/" + second.get("objectId"));
        // 降级前已确认的对象不受影响
        assertThat((List<?>) exchange(tenant, HttpMethod.GET, attachments(orderId), null)
                        .get("data"))
                .hasSize(1);
    }

    @Test
    void deletingAttachmentRemovesFileAndFreesQuotaButOnlyWithinOwnTenant() throws Exception {
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
        assertThat(objectKeys()).doesNotContain(tenant + "/demo-service/" + objectId);
        assertThat(exchange(tenant, HttpMethod.GET, attachments(orderId) + "/" + attachmentId + "/download", null)
                        .get("code"))
                .isEqualTo("attachment.notFound");
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.txt", size))
                        .get("httpStatus"))
                .isEqualTo(201);
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
