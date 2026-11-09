package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;

/** platform 预览 Plan 降级时经内部接口读取的成员数据：只统计本租户，失去 Permission Code 按实际生效的授权判断。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/fixtures/plan_impact.sql")
class PlanImpactTest {
    static final String SERVICE_TOKEN = "plan-impact-service-token";
    static final String TENANT_A = "019a0130-0000-7000-8000-0000000000a0";
    static final String TENANT_B = "019a0130-0000-7000-8000-0000000000b0";
    static final String TENANT_C = "019a0130-0000-7000-8000-0000000000c0";
    static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE = new ParameterizedTypeReference<>() {};
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.iam.signing-key-file", () -> "target/test-plan-impact-signing.jwk");
        registry.add("forge.iam.manifest.tokens.forge-platform", () -> SERVICE_TOKEN);
    }

    @LocalServerPort
    int port;

    @Test
    void countsOnlyActiveMembershipsThatEffectivelyHoldALostPermission() {
        var impact = impact(TENANT_A, List.of("order:approve"), List.of());

        // 成员数与配额校验口径一致：除已离开外都占名额（管理员、审批员、只读、已停用、已邀请）
        assertThat(impact).containsEntry("memberCount", 5);
        // 管理员经预置角色持有、审批员经自定义角色持有；已停用与已离开的审批员不算
        assertThat(impact).containsEntry("affectedMemberships", 2);
    }

    @Test
    void permissionHoldersOfOtherTenantsAreNotCounted() {
        var impact = impact(TENANT_B, List.of("order:approve"), List.of());

        assertThat(impact).containsEntry("memberCount", 1).containsEntry("affectedMemberships", 1);
    }

    @Test
    void permissionsAlreadyOutsideTheCurrentPlanAreNotLost() {
        var impact = impact(TENANT_C, List.of("order:approve"), List.of());

        assertThat(impact).containsEntry("memberCount", 2).containsEntry("affectedMemberships", 0);
    }

    @Test
    void losingASubAppAffectsEveryActiveMembership() {
        var impact = impact(TENANT_A, List.of(), List.of("demo"));

        assertThat(impact).containsEntry("memberCount", 5).containsEntry("affectedMemberships", 3);
    }

    @Test
    void nothingLostAffectsNobodyButStillReportsMemberCount() {
        var impact = impact(TENANT_A, List.of(), List.of());

        assertThat(impact).containsEntry("memberCount", 5).containsEntry("affectedMemberships", 0);
    }

    @Test
    void requiresAConfiguredServiceToken() {
        for (String token : new String[] {null, "wrong-service-token"}) {
            var response = post(TENANT_A, Map.of("lostPermissions", List.of(), "lostSubApps", List.of()), token);
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody().get("code")).isEqualTo("auth.unauthenticated");
        }
    }

    @Test
    void rejectsARequestWithoutTheLostLists() {
        var response = post(TENANT_A, Map.of(), SERVICE_TOKEN);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code")).isEqualTo("request.invalid");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> impact(String tenantId, List<String> lostPermissions, List<String> lostSubApps) {
        var response =
                post(tenantId, Map.of("lostPermissions", lostPermissions, "lostSubApps", lostSubApps), SERVICE_TOKEN);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return (Map<String, Object>) response.getBody().get("data");
    }

    private ResponseEntity<Map<String, Object>> post(String tenantId, Object body, String serviceToken) {
        return RestClient.create("http://127.0.0.1:" + port)
                .post()
                .uri("/internal/tenants/" + tenantId + "/plan-impact")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .headers(headers -> {
                    if (serviceToken != null) headers.setBearerAuth(serviceToken);
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toEntity(ENVELOPE);
    }
}
