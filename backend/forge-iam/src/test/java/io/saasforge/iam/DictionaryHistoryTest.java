package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.referencedata.service.DictionaryHistoryService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantDeleted;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** 字典管理 HTTP 写入与公开历史读取接缝；查询 HTTP 接口由字典历史工单 02 覆盖。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/reference_data.sql")
class DictionaryHistoryTest {
    static final String KEY_FILE = "target/test-dictionary-history-signing.jwk";
    static final String SERVICE = "refdata-service";
    static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-0000000002c1");
    static final UUID ADMIN = UUID.fromString("019a0110-0000-7000-8000-0000000002c3");
    static final UUID ADMIN_B = UUID.fromString("019a0110-0000-7000-8000-0000000002c7");
    static final UUID IDENTITY = UUID.fromString("019a0110-0000-7000-8000-0000000002c2");
    static final UUID IDENTITY_B = UUID.fromString("019a0110-0000-7000-8000-0000000002c6");
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("forge.iam.signing-key-file", () -> KEY_FILE);
        registry.add("forge.iam.manifest.tokens." + SERVICE, () -> "refdata-service-token");
    }

    @LocalServerPort
    int port;

    @Autowired
    DictionaryHistoryService history;

    @Autowired
    List<EventHandler<?>> handlers;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void creatingACustomItemRecordsOnlyTheTenantOverridesAndTheActor() throws Exception {
        Instant before = Instant.now();
        assertThat(call(TENANT, HttpMethod.POST, "grade", null, Map.of("code", "history", "name", "历史客户"))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
        CurrentTenant.run(TENANT, () -> {
            var versions = history.list(TENANT, SERVICE, "grade");
            assertThat(versions).hasSize(1);
            var created = versions.get(0);
            assertThat(created.version()).isEqualTo(1);
            assertThat(created.changedBy()).isEqualTo(IDENTITY);
            assertThat(created.changedAt()).isAfterOrEqualTo(before);
            assertThat(created.summary()).contains("custom.history");
            assertThat(created.snapshot()).singleElement().satisfies(item -> {
                assertThat(item.code()).isEqualTo("custom.history");
                assertThat(item.name()).isEqualTo("历史客户");
                assertThat(item.sort()).isEqualTo(2);
                assertThat(item.custom()).isTrue();
                assertThat(item.enabled()).isTrue();
            });
        });
    }

    @Test
    void renameSortDisableAndEnableEachPreserveTheCompleteOverrideSnapshot() throws Exception {
        assertThat(call(TENANT, HttpMethod.PUT, "level", "high", Map.of("name", "最高", "sort", 0))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(TENANT, HttpMethod.PUT, "level", "high", Map.of("name", "最高", "sort", 5))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(TENANT, HttpMethod.POST, "level", "low/disable", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(TENANT, HttpMethod.POST, "level", "low/enable", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        CurrentTenant.run(TENANT, () -> {
            var versions = history.list(TENANT, SERVICE, "level");
            assertThat(versions).extracting(version -> version.version()).containsExactly(4L, 3L, 2L, 1L);
            assertThat(versions.get(3).summary()).contains("high", "显示名", "排序");
            assertThat(versions.get(2).summary()).contains("high", "排序");
            assertThat(versions.get(3).snapshot()).singleElement().satisfies(item -> {
                assertThat(item.name()).isEqualTo("最高");
                assertThat(item.sort()).isEqualTo(0);
            });
            assertThat(versions.get(2).snapshot())
                    .singleElement()
                    .satisfies(item -> assertThat(item.sort()).isEqualTo(5));
            assertThat(versions.get(1).snapshot()).hasSize(2).anySatisfy(item -> {
                assertThat(item.code()).isEqualTo("low");
                assertThat(item.name()).isNull();
                assertThat(item.sort()).isNull();
                assertThat(item.enabled()).isFalse();
            });
            assertThat(versions.get(0).snapshot())
                    .hasSize(2)
                    .allSatisfy(item -> assertThat(item.enabled()).isTrue());
        });
    }

    @Test
    void unchangedSavesAndRepeatedEnableDisableDoNotCreateOverridesOrHistory() throws Exception {
        assertThat(call(TENANT_B, HttpMethod.PUT, "channel", "store", Map.of("name", "门店", "sort", 2))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(TENANT_B, HttpMethod.POST, "channel", "web/enable", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        CurrentTenant.run(
                TENANT_B,
                () -> assertThat(history.list(TENANT_B, SERVICE, "channel")).isEmpty());

        assertThat(call(TENANT_B, HttpMethod.POST, "channel", null, Map.of("code", "loop", "name", "循环"))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
        List<ResponseEntity<Map>> saves = List.of(
                call(TENANT_B, HttpMethod.PUT, "channel", "custom.loop", Map.of("name", "循环", "sort", 3)),
                call(TENANT_B, HttpMethod.POST, "channel", "custom.loop/disable", null),
                call(TENANT_B, HttpMethod.POST, "channel", "custom.loop/disable", null),
                call(TENANT_B, HttpMethod.POST, "channel", "custom.loop/enable", null),
                call(TENANT_B, HttpMethod.POST, "channel", "custom.loop/enable", null));
        assertThat(saves)
                .allSatisfy(
                        response -> assertThat(response.getStatusCode().value()).isEqualTo(200));

        CurrentTenant.run(TENANT_B, () -> {
            var versions = history.list(TENANT_B, SERVICE, "channel");
            assertThat(versions).extracting(version -> version.version()).containsExactly(3L, 2L, 1L);
            assertThat(versions)
                    .allSatisfy(version -> assertThat(version.snapshot())
                            .singleElement()
                            .satisfies(item -> assertThat(item.code()).isEqualTo("custom.loop")));
        });
    }

    @Test
    void tenantDeletionCleansHistoryIdempotentlyWithoutTouchingOtherTenants() throws Exception {
        assertThat(call(TENANT_B, HttpMethod.POST, "priority", null, Map.of("code", "cleanup", "name", "待清理"))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
        assertThat(call(TENANT, HttpMethod.POST, "priority", null, Map.of("code", "keep", "name", "保留"))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
        CurrentTenant.run(
                TENANT_B,
                () -> assertThat(history.list(TENANT_B, SERVICE, "priority")).hasSize(1));

        @SuppressWarnings("unchecked")
        EventHandler<TenantDeleted> handler = (EventHandler<TenantDeleted>) handlers.stream()
                .filter(candidate -> candidate.eventType().equals(TenantDeleted.class))
                .findFirst()
                .orElseThrow();
        for (int delivery = 0; delivery < 2; delivery++) {
            CurrentTenant.run(
                    TENANT_B,
                    () -> transactions.executeWithoutResult(status -> handler.handle(new TenantDeleted(TENANT_B))));
        }

        CurrentTenant.run(
                TENANT_B,
                () -> assertThat(history.list(TENANT_B, SERVICE, "priority")).isEmpty());
        CurrentTenant.run(
                TENANT,
                () -> assertThat(history.list(TENANT, SERVICE, "priority")).hasSize(1));
    }

    @Test
    void historiesAndVersionSequencesAreIsolatedByTenantAndRls() throws Exception {
        assertThat(call(TENANT, HttpMethod.POST, "customer-source", null, Map.of("code", "isolated", "name", "租户甲"))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
        assertThat(call(TENANT_B, HttpMethod.POST, "customer-source", null, Map.of("code", "isolated", "name", "租户乙"))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
        assertThat(call(TENANT, HttpMethod.POST, "customer-source", null, Map.of("code", "isolated", "name", "重复编码"))
                        .getStatusCode()
                        .value())
                .isEqualTo(409);

        CurrentTenant.run(TENANT, () -> {
            var own = history.list(TENANT, SERVICE, "customer-source");
            assertThat(own).singleElement().satisfies(version -> {
                assertThat(version.version()).isEqualTo(1);
                assertThat(version.snapshot())
                        .singleElement()
                        .satisfies(item -> assertThat(item.name()).isEqualTo("租户甲"));
            });
            assertThat(history.list(TENANT_B, SERVICE, "customer-source")).isEmpty();
            assertThat(history.list(TENANT, "other-service", "customer-source")).isEmpty();
        });
        CurrentTenant.run(
                TENANT_B,
                () -> assertThat(history.list(TENANT_B, SERVICE, "customer-source"))
                        .singleElement()
                        .satisfies(version -> {
                            assertThat(version.version()).isEqualTo(1);
                            assertThat(version.changedBy()).isEqualTo(IDENTITY_B);
                            assertThat(version.snapshot())
                                    .singleElement()
                                    .satisfies(item -> assertThat(item.name()).isEqualTo("租户乙"));
                        }));
        assertThat(history.list(TENANT, SERVICE, "customer-source")).isEmpty();
    }

    @Test
    void concurrentChangesReceiveConsecutiveVersionsWithCompleteSnapshots() throws Exception {
        var ready = new CountDownLatch(3);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(3);
        try {
            var requests = List.of("first", "second", "third").stream()
                    .map(code -> workers.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        return call(TENANT_B, HttpMethod.POST, "grade", null, Map.of("code", code, "name", code));
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var request : requests) {
                assertThat(request.get(15, TimeUnit.SECONDS).getStatusCode().value())
                        .isEqualTo(201);
            }
        } finally {
            start.countDown();
            workers.shutdownNow();
        }

        CurrentTenant.run(TENANT_B, () -> {
            var versions = history.list(TENANT_B, SERVICE, "grade");
            assertThat(versions).extracting(version -> version.version()).containsExactly(3L, 2L, 1L);
            assertThat(versions.get(0).snapshot())
                    .extracting(item -> item.code())
                    .containsExactly("custom.first", "custom.second", "custom.third");
            assertThat(versions.get(1).snapshot()).hasSize(2);
            assertThat(versions.get(2).snapshot()).hasSize(1);
            assertThat(versions.get(0).snapshot()).containsAll(versions.get(1).snapshot());
            assertThat(versions.get(1).snapshot()).containsAll(versions.get(2).snapshot());
        });
    }

    @Test
    void theFirstSnapshotIncludesOverridesCreatedBeforeHistoryWasAvailable() throws Exception {
        assertThat(call(TENANT, HttpMethod.PUT, "region", "custom.south", Map.of("name", "新南方", "sort", 4))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        CurrentTenant.run(
                TENANT,
                () -> assertThat(history.list(TENANT, SERVICE, "region"))
                        .singleElement()
                        .satisfies(version -> {
                            assertThat(version.version()).isEqualTo(1);
                            assertThat(version.snapshot())
                                    .extracting(item -> item.code())
                                    .containsExactly("custom.north", "custom.south");
                            assertThat(version.snapshot()).anySatisfy(item -> {
                                assertThat(item.code()).isEqualTo("custom.south");
                                assertThat(item.name()).isEqualTo("新南方");
                                assertThat(item.enabled()).isFalse();
                            });
                        }));
    }

    private ResponseEntity<Map> call(UUID tenant, HttpMethod method, String dictionary, String suffix, Object body)
            throws Exception {
        String uri = "/reference-data/dictionaries/" + SERVICE + "/" + dictionary + "/items"
                + (suffix == null ? "" : "/" + suffix);
        String token = token(tenant);
        var request = RestClient.create("http://127.0.0.1:" + port)
                .method(method)
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(token));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {})
                .toEntity(Map.class);
    }

    private static String token(UUID tenant) throws Exception {
        RSAKey key = RSAKey.parse(Files.readString(Path.of(KEY_FILE)));
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8080/iam")
                .subject((tenant.equals(TENANT) ? IDENTITY : IDENTITY_B).toString())
                .audience("tenant-app")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("tenant_id", tenant.toString())
                .claim("membership_id", (tenant.equals(TENANT) ? ADMIN : ADMIN_B).toString())
                .claim("ver", 1)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
