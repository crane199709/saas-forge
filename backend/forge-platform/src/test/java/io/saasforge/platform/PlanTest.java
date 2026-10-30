package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.platform.tenant.mapper.OutboxAuditMapper;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/** Operator 维护套餐并分配给租户，分配与套餐内容的变更经 Outbox 广播 TenantPlanChanged（工单 20）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/plan.sql")
class PlanTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE = new ParameterizedTypeReference<>() {};

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../deploy/postgres/init"), "/docker-entrypoint-initdb.d/");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        PlatformTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper outbox;

    @Test
    void featurePackIsChosenFromTheIamCatalog() {
        Response catalog = call("GET", "/plans/permissions", null);
        assertThat(catalog.status()).isEqualTo(200);
        assertThat(catalog.list())
                .extracting(item -> item.get("code"))
                .containsExactly("order:read", "order:approve", "report:export");

        Response basic = call("POST", "/plans", plan("catalog-basic", List.of("order:read"), List.of(), 5, null));
        assertThat(basic.status()).isEqualTo(201);
        assertThat(basic.data())
                .containsEntry("code", "catalog-basic")
                .containsEntry("permissions", List.of("order:read"))
                .containsEntry("memberQuota", 5)
                .containsEntry("storageQuotaMb", null);
        Response pro = call(
                "POST",
                "/plans",
                plan("catalog-pro", List.of("report:export", "order:read"), List.of("demo", "billing"), null, 1024L));
        // Feature Pack 按编码排序存储
        assertThat(pro.data())
                .containsEntry("permissions", List.of("order:read", "report:export"))
                .containsEntry("subApps", List.of("billing", "demo"))
                .containsEntry("storageQuotaMb", 1024);

        assertThat(call("POST", "/plans", plan("catalog-basic", List.of(), List.of(), null, null))
                        .code())
                .isEqualTo("plan.codeTaken");
        Response unknown = call("POST", "/plans", plan("catalog-x", List.of("order:delete"), List.of(), null, null));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.code()).isEqualTo("plan.unknownPermission");
        assertThat(call("POST", "/plans", plan("catalog-y", List.of(), List.of("Bad App"), null, null))
                        .status())
                .isEqualTo(400);
        assertThat(call("POST", "/plans", plan("catalog-z", List.of(), List.of(), 0, null))
                        .status())
                .isEqualTo(400);

        assertThat(call("GET", "/plans", null).list())
                .extracting(item -> item.get("code"))
                .containsSubsequence("catalog-basic", "catalog-pro", "default");
    }

    @Test
    void assignmentAndPlanEditsAreBroadcastToTenants() {
        UUID basic = id(call("POST", "/plans", plan("bcast-basic", List.of("order:read"), List.of(), 3, null)));
        UUID pro = id(call(
                "POST",
                "/plans",
                plan("bcast-pro", List.of("order:read", "order:approve"), List.of("demo"), 50, 2048L)));

        Response created = call(
                "POST",
                "/tenants",
                Map.of("name", "套餐租户", "adminContact", "plan-owner@example.com", "planId", basic.toString()));
        assertThat(created.status()).isEqualTo(201);
        UUID tenantId = UUID.fromString((String) tenant(created).get("id"));
        assertThat(tenant(created)).containsEntry("planId", basic.toString());
        // 创建租户与初始 Plan 同一事务广播，事件带该租户的上下文
        assertThat(events(tenantId))
                .containsExactly(new TenantPlanChanged(
                        tenantId, basic, "bcast-basic", List.of("order:read"), List.of(), 3, null, 1));

        Response upgraded = changePlan(tenantId, pro);
        assertThat(upgraded.status()).isEqualTo(200);
        assertThat(tenant(upgraded)).containsEntry("planId", pro.toString());
        // Plan 不变时不再广播
        changePlan(tenantId, pro);
        assertThat(events(tenantId))
                .last()
                .isEqualTo(new TenantPlanChanged(
                        tenantId,
                        pro,
                        "bcast-pro",
                        List.of("order:approve", "order:read"),
                        List.of("demo"),
                        50,
                        2048L,
                        2));
        assertThat(changePlan(tenantId, UUID.randomUUID()).code()).isEqualTo("plan.notFound");

        // 修改 Plan 的内容即修改使用它的租户的上限：降级广播给该租户
        Response edited = call(
                "PUT",
                "/plans/" + pro,
                Map.of("name", "专业版", "permissions", List.of("order:read"), "subApps", List.of()));
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.data()).containsEntry("memberQuota", null).containsEntry("storageQuotaMb", null);
        assertThat(events(tenantId))
                .hasSize(3)
                .last()
                .isEqualTo(new TenantPlanChanged(
                        tenantId, pro, "bcast-pro", List.of("order:read"), List.of(), null, null, 3));

        // 仍有租户使用的 Plan 不能删除
        assertThat(call("DELETE", "/plans/" + pro, null).code()).isEqualTo("plan.inUse");
        changePlan(tenantId, basic);
        assertThat(call("DELETE", "/plans/" + pro, null).status()).isEqualTo(200);
        assertThat(call("GET", "/plans", null).list())
                .extracting(item -> item.get("code"))
                .doesNotContain("bcast-pro");
        assertThat(call("PUT", "/plans/" + pro, Map.of("name", "x", "permissions", List.of(), "subApps", List.of()))
                        .code())
                .isEqualTo("plan.notFound");
        assertThat(changePlan(tenantId, pro).code()).isEqualTo("plan.notFound");
        assertThat(events(tenantId)).hasSize(4);
    }

    private static Map<String, Object> plan(
            String code, List<String> permissions, List<String> subApps, Integer memberQuota, Long storageQuotaMb) {
        var body = new HashMap<String, Object>();
        body.put("code", code);
        body.put("name", code);
        body.put("permissions", permissions);
        body.put("subApps", subApps);
        body.put("memberQuota", memberQuota);
        body.put("storageQuotaMb", storageQuotaMb);
        return body;
    }

    private static UUID id(Response response) {
        assertThat(response.status()).isEqualTo(201);
        return UUID.fromString((String) response.data().get("id"));
    }

    private Response changePlan(UUID tenantId, UUID planId) {
        return call("PUT", "/tenants/" + tenantId + "/plan", Map.of("planId", planId.toString()));
    }

    private List<TenantPlanChanged> events(UUID tenantId) {
        return outbox.payloads(tenantId, TenantPlanChanged.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, TenantPlanChanged.class))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tenant(Response response) {
        return (Map<String, Object>) response.data().get("tenant");
    }

    private Response call(String method, String path, Object body) {
        try {
            var request = RestClient.create("http://localhost:" + port)
                    .method(HttpMethod.valueOf(method))
                    .uri(path)
                    .header("Authorization", "Bearer " + PlatformTokens.operator());
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange(
                    (req, response) -> new Response(response.getStatusCode().value(), response.bodyTo(ENVELOPE)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record Response(int status, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list() {
            return (List<Map<String, Object>>) body.get("data");
        }

        String code() {
            return (String) body.get("code");
        }
    }
}
