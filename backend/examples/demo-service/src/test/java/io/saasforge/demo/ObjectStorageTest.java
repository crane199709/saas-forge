package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import io.saasforge.sdk.storage.ObjectStorage;
import io.saasforge.sdk.storage.StorageErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

/** 订单附件经 SDK 对象存储上传、下载与删除（工单 24）：key 前缀由 SDK 强制、跨租户访问被拒、超出存储容量 Quota 的上传被拒。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObjectStorageTest {
    private static final String BUCKET = "forge-test";
    private static final String ACCESS_KEY = "storage-test";
    private static final String SECRET_KEY = "storage-test-secret";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../../deploy/postgres/init"), "/docker-entrypoint-initdb.d/");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

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
        registry.add("forge.storage.endpoint", ObjectStorageTest::endpoint);
        registry.add("forge.storage.bucket", () -> BUCKET);
        registry.add("forge.storage.access-key", () -> ACCESS_KEY);
        registry.add("forge.storage.secret-key", () -> SECRET_KEY);
        TestTokens.register(registry);
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

    private UUID tenant;

    @BeforeEach
    void newTenant() {
        tenant = UUID.randomUUID();
        TestGrants.publishAll(redisTemplate, tenant, TestTokens.MEMBERSHIP_ID);
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

        Map<?, ?> first = prepareUpload(tenant, orderId, "first.bin", size);
        // 未确认的上传申请同样占用容量，并发申请不会合计超出 Quota
        Map<String, Object> reserved =
                exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.bin", size));
        assertThat(reserved.get("code")).isEqualTo("storage.quotaExceeded");
        assertThat(reserved.get("httpStatus")).isEqualTo(409);
        assertThat(reserved.get("userTip")).isEqualTo("存储空间已达套餐上限，无法继续上传");

        put(first, new byte[size]);
        assertThat(exchange(tenant, HttpMethod.POST, attachments(orderId), Map.of("objectId", first.get("objectId")))
                        .get("httpStatus"))
                .isEqualTo(201);
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.bin", size))
                        .get("code"))
                .isEqualTo("storage.quotaExceeded");
        // 未超出时照常允许
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("small.bin", 1024))
                        .get("httpStatus"))
                .isEqualTo(201);

        // 重复或乱序送达的旧版本不生效
        deliver(planChanged(null, 1));
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.bin", size))
                        .get("code"))
                .isEqualTo("storage.quotaExceeded");

        deliver(planChanged(null, 2));
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.bin", size))
                        .get("httpStatus"))
                .isEqualTo(201);
    }

    @Test
    void uploadConfirmedAfterDowngradeBeyondQuotaIsRejectedAndRemoved() throws Exception {
        String orderId = createOrder(tenant);
        int size = 600 * 1024;
        Map<?, ?> first = prepareUpload(tenant, orderId, "first.bin", size);
        Map<?, ?> second = prepareUpload(tenant, orderId, "second.bin", size);
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
        Map<?, ?> ticket = prepareUpload(tenant, orderId, "first.bin", size);
        put(ticket, new byte[size]);
        String objectId = (String) ticket.get("objectId");
        String attachmentId = (String)
                ((Map<?, ?>) exchange(tenant, HttpMethod.POST, attachments(orderId), Map.of("objectId", objectId))
                                .get("data"))
                        .get("id");
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.bin", size))
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
        assertThat(exchange(tenant, HttpMethod.POST, uploads(orderId), uploadRequest("second.bin", size))
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
}
