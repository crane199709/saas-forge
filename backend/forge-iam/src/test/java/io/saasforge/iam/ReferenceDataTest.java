package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.ReferenceDataChanged;
import io.saasforge.sdk.provisioning.TenantDeleted;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/** 租户对 Extensible Dictionary 与 Parameter 的修改、成员可读的字典接口、变更事件与租户清理（参考数据工单 02）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/reference_data.sql")
class ReferenceDataTest {
    static final String KEY_FILE = "target/test-reference-data-signing.jwk";
    static final String SERVICE = "refdata-service";
    static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-0000000002c1");
    static final UUID ADMIN = UUID.fromString("019a0110-0000-7000-8000-0000000002c3");
    static final UUID MEMBER = UUID.fromString("019a0110-0000-7000-8000-0000000002c5");
    static final UUID ADMIN_B = UUID.fromString("019a0110-0000-7000-8000-0000000002c7");
    static final Map<UUID, UUID> IDENTITIES = Map.of(
            ADMIN, UUID.fromString("019a0110-0000-7000-8000-0000000002c2"),
            MEMBER, UUID.fromString("019a0110-0000-7000-8000-0000000002c4"),
            ADMIN_B, UUID.fromString("019a0110-0000-7000-8000-0000000002c6"));
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../deploy/postgres/init"), "/docker-entrypoint-initdb.d/");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> KEY_FILE);
    }

    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper outbox;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void everyMemberReadsTheMergedDictionariesWithItemsNoLongerSelectableFlagged() throws Exception {
        var response = call(MEMBER, HttpMethod.GET, "/reference-data/dictionaries", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> source = dictionary(response, "customer-source");
        assertThat(source.get("name")).isEqualTo("客户来源");
        assertThat(source.get("extensible")).isEqualTo(true);
        // v2 删除的内置项 legacy 仍返回，标记为下线，供历史数据显示名称。
        assertThat(items(source))
                .containsExactly(
                        Map.of("code", "online", "name", "线上", "sort", 1, "custom", false, "status", "enabled"),
                        Map.of("code", "referral", "name", "转介绍", "sort", 2, "custom", false, "status", "enabled"),
                        Map.of("code", "legacy", "name", "旧渠道", "sort", 2, "custom", false, "status", "retired"));
        assertThat(dictionary(response, "order-status").get("extensible")).isEqualTo(false);
    }

    @Test
    void customItemsGetThePrefixAndTheirCodeIsUniqueWithinTheTenantDictionary() throws Exception {
        var created = createItem(ADMIN, "grade", "vip", "贵宾");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(data(created))
                .containsEntry("code", "custom.vip")
                .containsEntry("name", "贵宾")
                .containsEntry("custom", true)
                .containsEntry("status", "enabled")
                .containsEntry("sort", 2);

        var again = createItem(ADMIN, "grade", "vip", "贵宾二");
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().get("code")).isEqualTo("referenceData.itemCodeTaken");
        // 另一个租户可以使用同一编码。
        assertThat(createItem(ADMIN_B, "grade", "vip", "贵宾").getStatusCode().value())
                .isEqualTo(201);
        // 租户填写的部分不能自带非法字符。
        assertThat(createItem(ADMIN, "grade", "Bad Code", "坏").getStatusCode().value())
                .isEqualTo(400);

        // 自定义项只能停用，停用后仍在字典中返回并带标记。
        assertThat(call(ADMIN, HttpMethod.POST, itemPath("grade", "custom.vip") + "/disable", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(items(dictionary(call(MEMBER, HttpMethod.GET, "/reference-data/dictionaries", null), "grade")))
                .extracting(item -> item.get("code") + ":" + item.get("status"))
                .containsExactly("normal:enabled", "custom.vip:disabled");
        assertThat(call(ADMIN, HttpMethod.DELETE, itemPath("grade", "custom.vip"), null)
                        .getStatusCode()
                        .value())
                .isNotEqualTo(200);
    }

    @Test
    void builtInItemsCanBeRenamedReorderedAndDisabledButFixedDictionariesAreReadOnly() throws Exception {
        var renamed = call(ADMIN, HttpMethod.PUT, itemPath("level", "high"), Map.of("name", "最高", "sort", 0));
        assertThat(renamed.getStatusCode().value()).isEqualTo(200);
        assertThat(data(renamed)).containsEntry("code", "high").containsEntry("name", "最高");
        call(ADMIN, HttpMethod.POST, itemPath("level", "low") + "/disable", null);

        assertThat(items(dictionary(call(MEMBER, HttpMethod.GET, "/reference-data/dictionaries", null), "level")))
                .extracting(item -> item.get("code") + ":" + item.get("name") + ":" + item.get("status"))
                .containsExactly("high:最高:enabled", "low:低:disabled");
        assertThat(call(ADMIN, HttpMethod.POST, itemPath("level", "low") + "/enable", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        var fixed = call(ADMIN, HttpMethod.PUT, itemPath("order-status", "open"), Map.of("name", "进行中", "sort", 1));
        assertThat(fixed.getStatusCode().value()).isEqualTo(409);
        assertThat(fixed.getBody().get("code")).isEqualTo("referenceData.dictionaryReadOnly");
        assertThat(createItem(ADMIN, "order-status", "x", "其他").getBody().get("code"))
                .isEqualTo("referenceData.dictionaryReadOnly");
        assertThat(call(ADMIN, HttpMethod.POST, itemPath("customer-source", "legacy") + "/enable", null)
                        .getBody()
                        .get("code"))
                .isEqualTo("referenceData.itemNotFound");
        assertThat(createItem(ADMIN, "missing", "x", "其他").getBody().get("code"))
                .isEqualTo("referenceData.dictionaryNotFound");
    }

    @Test
    void selectableItemsCannotShareADisplayName() throws Exception {
        var duplicate = createItem(ADMIN, "channel", "site", "网站");
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody().get("code")).isEqualTo("referenceData.itemNameTaken");
        assertThat(call(ADMIN, HttpMethod.PUT, itemPath("channel", "store"), Map.of("name", "网站", "sort", 2))
                        .getBody()
                        .get("code"))
                .isEqualTo("referenceData.itemNameTaken");

        // 停用的项不再占用名称；它再启用时与现有可选项重名即拒绝。
        call(ADMIN, HttpMethod.POST, itemPath("channel", "web") + "/disable", null);
        assertThat(createItem(ADMIN, "channel", "site", "网站").getStatusCode().value())
                .isEqualTo(201);
        var enable = call(ADMIN, HttpMethod.POST, itemPath("channel", "web") + "/enable", null);
        assertThat(enable.getStatusCode().value()).isEqualTo(409);
        assertThat(enable.getBody().get("code")).isEqualTo("referenceData.itemNameTaken");
    }

    @Test
    void onlyEditableParametersCanBeOverriddenWithinTheirConstraintsAndReset() throws Exception {
        assertThat(parameters(ADMIN))
                .extracting(parameter -> parameter.get("code") + "=" + parameter.get("value"))
                .containsExactly("order.auto-close=PT30M", "order.max-items=50", "customer.default-source=online");

        var overridden = call(ADMIN, HttpMethod.PUT, parameterPath("order.max-items"), Map.of("value", 100));
        assertThat(overridden.getStatusCode().value()).isEqualTo(200);
        assertThat(data(overridden))
                .containsEntry("value", 100)
                .containsEntry("defaultValue", 50)
                .containsEntry("overridden", true)
                .containsEntry("min", 1)
                .containsEntry("max", 200);
        for (Object invalid : List.of(500, "abc", 1.5)) {
            var rejected = call(ADMIN, HttpMethod.PUT, parameterPath("order.max-items"), Map.of("value", invalid));
            assertThat(rejected.getStatusCode().value()).isEqualTo(400);
            assertThat(rejected.getBody().get("code")).isEqualTo("referenceData.invalidParameterValue");
        }
        var duration = call(ADMIN, HttpMethod.PUT, parameterPath("order.auto-close"), Map.of("value", "PT1M"));
        assertThat(duration.getBody().get("code")).isEqualTo("referenceData.invalidParameterValue");
        // dictionary 类型的覆盖值须是租户字典中的可选项：下线项不行，可选项可以。
        assertThat(call(ADMIN, HttpMethod.PUT, parameterPath("customer.default-source"), Map.of("value", "legacy"))
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
        assertThat(call(ADMIN, HttpMethod.PUT, parameterPath("customer.default-source"), Map.of("value", "referral"))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        var fixed = call(ADMIN, HttpMethod.PUT, parameterPath("order.remark"), Map.of("value", "好"));
        assertThat(fixed.getStatusCode().value()).isEqualTo(409);
        assertThat(fixed.getBody().get("code")).isEqualTo("referenceData.parameterNotEditable");
        // 新版本删除的参数的覆盖值保留但不生效，也不能再修改。
        assertThat(call(ADMIN, HttpMethod.PUT, parameterPath("order.removed"), Map.of("value", 3))
                        .getBody()
                        .get("code"))
                .isEqualTo("referenceData.parameterNotFound");

        var reset = call(ADMIN, HttpMethod.DELETE, parameterPath("order.max-items"), null);
        assertThat(reset.getStatusCode().value()).isEqualTo(200);
        assertThat(parameters(ADMIN))
                .extracting(parameter -> parameter.get("code") + "=" + parameter.get("value"))
                .containsExactly("order.auto-close=PT30M", "order.max-items=50", "customer.default-source=referral");
    }

    @Test
    void changesPublishReferenceDataChangedForTheTenantAndAreAudited() throws Exception {
        int before = changed(TENANT_B);
        createItem(ADMIN_B, "channel", "phone", "电话");
        call(ADMIN_B, HttpMethod.PUT, itemPath("channel", "custom.phone"), Map.of("name", "电话渠道", "sort", 5));
        call(ADMIN_B, HttpMethod.POST, itemPath("channel", "custom.phone") + "/disable", null);
        call(ADMIN_B, HttpMethod.POST, itemPath("channel", "custom.phone") + "/enable", null);
        call(ADMIN_B, HttpMethod.PUT, parameterPath("order.max-items"), Map.of("value", 10));
        call(ADMIN_B, HttpMethod.DELETE, parameterPath("order.max-items"), null);

        assertThat(changed(TENANT_B)).isEqualTo(before + 6);
        assertThat(outbox.payloads(TENANT_B, ReferenceDataChanged.class.getSimpleName()).stream()
                        .map(payload -> JSON.readValue(payload, ReferenceDataChanged.class)))
                .allMatch(event -> event.tenantId().equals(TENANT_B));
        List<AuditEvent> audits = outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .filter(event -> TENANT_B.equals(event.tenantId()))
                .toList();
        assertThat(audits)
                .extracting(AuditEvent::action)
                .contains(
                        "dictionary.itemCreate",
                        "dictionary.itemUpdate",
                        "dictionary.itemDisable",
                        "dictionary.itemEnable",
                        "parameter.override",
                        "parameter.reset");
        // 被拒绝的修改不发事件。
        createItem(ADMIN_B, "channel", "phone", "电话");
        assertThat(changed(TENANT_B)).isEqualTo(before + 6);
    }

    @Test
    void managementRequiresThePermissionButReadingDictionariesDoesNot() throws Exception {
        var create = createItem(MEMBER, "grade", "member", "成员建的");
        assertThat(create.getStatusCode().value()).isEqualTo(403);
        assertThat(create.getBody().get("code")).isEqualTo("auth.forbidden");
        assertThat(call(MEMBER, HttpMethod.GET, "/reference-data/parameters", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(call(MEMBER, HttpMethod.PUT, parameterPath("order.max-items"), Map.of("value", 10))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(call(MEMBER, HttpMethod.GET, "/reference-data/dictionaries", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
    }

    @Test
    void deletingATenantRemovesItsCustomItemsAndOverrides() throws Exception {
        createItem(ADMIN_B, "level", "mid", "中");
        call(ADMIN_B, HttpMethod.POST, itemPath("level", "low") + "/disable", null);
        call(ADMIN_B, HttpMethod.PUT, parameterPath("order.auto-close"), Map.of("value", "PT1H"));

        deliver(TENANT_B, new TenantDeleted(TENANT_B));
        deliver(TENANT_B, new TenantDeleted(TENANT_B));

        assertThat(items(dictionary(call(ADMIN_B, HttpMethod.GET, "/reference-data/dictionaries", null), "level")))
                .extracting(item -> item.get("code") + ":" + item.get("status"))
                .containsExactly("low:enabled", "high:enabled");
        assertThat(parameters(ADMIN_B))
                .extracting(parameter -> parameter.get("code") + "=" + parameter.get("value"))
                .contains("order.auto-close=PT30M");
    }

    private int changed(UUID tenant) {
        return outbox.payloads(tenant, ReferenceDataChanged.class.getSimpleName())
                .size();
    }

    /** 与 SDK 消费端一致：先恢复发布方的租户，再在事务中调用处理器。 */
    @SuppressWarnings("unchecked")
    private <T> void deliver(UUID tenantId, T event) {
        var handler = (EventHandler<T>) handlers.stream()
                .filter(candidate -> candidate.eventType() == event.getClass())
                .findFirst()
                .orElseThrow();
        CurrentTenant.run(tenantId, () -> transaction.executeWithoutResult(status -> handler.handle(event)));
    }

    private ResponseEntity<Map> createItem(UUID membership, String dictionary, String code, String name)
            throws Exception {
        return call(
                membership,
                HttpMethod.POST,
                "/reference-data/dictionaries/" + SERVICE + "/" + dictionary + "/items",
                Map.of("code", code, "name", name));
    }

    private static String itemPath(String dictionary, String code) {
        return "/reference-data/dictionaries/" + SERVICE + "/" + dictionary + "/items/" + code;
    }

    private static String parameterPath(String code) {
        return "/reference-data/parameters/" + SERVICE + "/" + code;
    }

    private List<Map<String, Object>> parameters(UUID membership) throws Exception {
        return list(call(membership, HttpMethod.GET, "/reference-data/parameters", null)).stream()
                .map(ReferenceDataTest::map)
                .filter(parameter -> SERVICE.equals(parameter.get("service")))
                .toList();
    }

    private static Map<String, Object> dictionary(ResponseEntity<Map> response, String code) {
        return list(response).stream()
                .map(ReferenceDataTest::map)
                .filter(dictionary -> SERVICE.equals(dictionary.get("service")) && code.equals(dictionary.get("code")))
                .findFirst()
                .orElseThrow();
    }

    private static List<Map<String, Object>> items(Map<String, Object> dictionary) {
        return ((List<?>) dictionary.get("items"))
                .stream().map(ReferenceDataTest::map).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<?> list(ResponseEntity<Map> response) {
        return (List<?>) response.getBody().get("data");
    }

    private ResponseEntity<Map> call(UUID membership, HttpMethod method, String uri, Object body) throws Exception {
        String token = token(membership);
        var request = client().method(method).uri(uri).headers(headers -> headers.setBearerAuth(token));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    // 以 iam 自己的签名密钥签发 Access Token，省去完整的登录与授权码流程；登录链路由 TenantLoginTest 覆盖。
    private static String token(UUID membership) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(KEY_FILE)));
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8080/iam")
                .subject(IDENTITIES.get(membership).toString())
                .audience("tenant-app")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", (membership.equals(ADMIN_B) ? TENANT_B : TENANT).toString())
                .claim("membership_id", membership.toString())
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private RestClient client() {
        return RestClient.create("http://127.0.0.1:" + port);
    }
}
