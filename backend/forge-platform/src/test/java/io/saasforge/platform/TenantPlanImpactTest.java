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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;

/** 更换租户 Plan 前的影响预览：只读，权限与更换 Plan 一致，iam 不可用时不给出零影响。 */
@Sql({"/fixtures/plan.sql", "/fixtures/plan_impact.sql"})
class TenantPlanImpactTest extends PlatformIntegrationTest {
    static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE = new ParameterizedTypeReference<>() {};
    static final String READ = "platform:tenant:read";
    static final String MANAGE = "platform:tenant:manage";
    static final String REGION_POLICY =
            "{\"attr\":\"resource.region\",\"op\":\"eq\",\"value\":{\"ref\":\"subject.attributes.region\"}}";
    static final UUID LEGACY_TENANT = UUID.fromString("019a0210-0000-7000-8000-000000000001");
    static final UUID STALE_PLAN = UUID.fromString("019a0210-0000-7000-8000-000000000021");
    static final String REGISTERED = "impact-registered";
    static final String KEPT = "impact-kept";

    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper outbox;

    @Test
    void downgradeShowsWhatIsLostAndDoesNotChangeThePlan() throws Exception {
        UUID pro = plan(List.of("order:read", "order:approve", "report:export"), List.of(KEPT, REGISTERED), 50);
        UUID basic = plan(List.of("order:read"), List.of(KEPT), 3);
        UUID tenant = tenant(pro, null);
        IamPlanImpactFixture.stub(tenant, 5, 2);
        int eventsBefore =
                outbox.payloads(tenant, TenantPlanChanged.class.getSimpleName()).size();

        Response impact = impact(PlatformTokens.operator(), tenant, basic);

        assertThat(impact.status()).isEqualTo(200);
        assertThat(impact.data())
                .containsEntry("lostPermissions", List.of("order:approve", "report:export"))
                .containsEntry("lostSubApps", List.of(REGISTERED))
                .containsEntry("memberCount", 5)
                .containsEntry("memberQuota", 3)
                .containsEntry("membersOverQuota", 2)
                .containsEntry("affectedMemberships", 2);
        var sent = IamPlanImpactFixture.received(tenant);
        assertThat(sent.path("lostPermissions").values())
                .extracting(node -> node.asString())
                .containsExactly("order:approve", "report:export");
        assertThat(sent.path("lostSubApps").values())
                .extracting(node -> node.asString())
                .containsExactly(REGISTERED);

        // 只读：Plan 不变，也不广播 TenantPlanChanged
        assertThat(tenantOf(call(PlatformTokens.operator(), "GET", "/tenants/" + tenant, null)))
                .containsEntry("planId", pro.toString());
        assertThat(outbox.payloads(tenant, TenantPlanChanged.class.getSimpleName()))
                .hasSize(eventsBefore);
    }

    @Test
    void upgradeIsEmptyAndDoesNotDependOnIam() throws Exception {
        UUID basic = plan(List.of("order:read"), List.of(), 3);
        UUID pro = plan(List.of("order:read", "order:approve"), List.of("demo"), 50);
        UUID tenant = tenant(basic, null);

        Response impact = impact(PlatformTokens.operator(), tenant, pro);

        assertThat(impact.status()).isEqualTo(200);
        assertEmpty(impact, 50);
        assertThat(IamPlanImpactFixture.received(tenant)).isNull();
    }

    @Test
    void choosingTheCurrentPlanIsEmpty() throws Exception {
        UUID basic = plan(List.of("order:read"), List.of(), 3);
        UUID tenant = tenant(basic, null);

        assertEmpty(impact(PlatformTokens.operator(), tenant, basic), 3);
        assertThat(IamPlanImpactFixture.received(tenant)).isNull();
    }

    @Test
    void loweringOnlyTheMemberQuotaReportsTheShortfallWithoutLosses() throws Exception {
        UUID large = plan(List.of("order:read"), List.of(), 50);
        UUID small = plan(List.of("order:read"), List.of(), 10);
        UUID tenant = tenant(large, null);
        IamPlanImpactFixture.stub(tenant, 12, 0);

        assertThat(impact(PlatformTokens.operator(), tenant, small).data())
                .containsEntry("lostPermissions", List.of())
                .containsEntry("lostSubApps", List.of())
                .containsEntry("memberCount", 12)
                .containsEntry("memberQuota", 10)
                .containsEntry("membersOverQuota", 2)
                .containsEntry("affectedMemberships", 0);
    }

    @Test
    void membersWithinTheNewQuotaAreNotOverQuota() throws Exception {
        UUID large = plan(List.of("order:read"), List.of(), 50);
        UUID small = plan(List.of("order:read"), List.of(), 10);
        UUID tenant = tenant(large, null);
        IamPlanImpactFixture.stub(tenant, 4, 0);

        assertThat(impact(PlatformTokens.operator(), tenant, small).data())
                .containsEntry("memberCount", 4)
                .containsEntry("membersOverQuota", 0);
    }

    @Test
    void iamUnavailableIsAnExplicitErrorRatherThanZeroImpact() throws Exception {
        UUID pro = plan(List.of("order:read", "order:approve"), List.of(), 50);
        UUID basic = plan(List.of("order:read"), List.of(), 3);
        UUID tenant = tenant(pro, null);

        Response impact = impact(PlatformTokens.operator(), tenant, basic);

        assertThat(impact.status()).isEqualTo(503);
        assertThat(impact.code()).isEqualTo("plan.impactUnavailable");
        assertThat(impact.body().get("data")).isNull();
    }

    @Test
    void tenantWithoutAPlanLosesEverythingOutsideTheNewPlan() throws Exception {
        UUID target = plan(List.of("order:read"), List.of("impact-kept"), 3);
        IamPlanImpactFixture.stub(LEGACY_TENANT, 4, 1);

        Response impact = impact(PlatformTokens.operator(), LEGACY_TENANT, target);

        assertThat(impact.status()).isEqualTo(200);
        assertThat(impact.data()).containsEntry("lostPermissions", List.of("order:approve", "report:export"));
        assertThat(lostSubApps(impact)).contains("impact-registered").doesNotContain("impact-kept");
        assertThat(impact.data()).containsEntry("memberCount", 4).containsEntry("membersOverQuota", 1);
    }

    @Test
    void retiredPermissionsAndDeletedSubAppsAreNotReportedAsLost() throws Exception {
        UUID basic = plan(List.of("order:read"), List.of(), 3);
        UUID tenant = tenant(STALE_PLAN, null);
        IamPlanImpactFixture.stub(tenant, 1, 1);

        Response impact = impact(PlatformTokens.operator(), tenant, basic);

        assertThat(impact.status()).isEqualTo(200);
        assertThat(impact.data())
                .containsEntry("lostPermissions", List.of("order:approve"))
                .containsEntry("lostSubApps", List.of(REGISTERED));
        var sent = IamPlanImpactFixture.received(tenant);
        assertThat(sent.path("lostPermissions").values())
                .extracting(node -> node.asString())
                .containsExactly("order:approve");
        assertThat(sent.path("lostSubApps").values())
                .extracting(node -> node.asString())
                .containsExactly(REGISTERED);
    }

    @Test
    void onlyRetiredOrDeletedCapabilitiesMissingFromTheTargetIsNotADowngrade() throws Exception {
        UUID target = plan(List.of("order:read", "order:approve"), List.of(REGISTERED), 50);
        UUID tenant = tenant(STALE_PLAN, null);

        assertEmpty(impact(PlatformTokens.operator(), tenant, target), 50);
        assertThat(IamPlanImpactFixture.received(tenant)).isNull();
    }

    @Test
    void catalogUnavailableIsAnExplicitErrorRatherThanZeroImpact() throws Exception {
        UUID pro = plan(List.of("order:read", "order:approve"), List.of(), 50);
        UUID basic = plan(List.of("order:read"), List.of(), 3);
        UUID tenant = tenant(pro, null);
        PlatformTokens.catalogAvailable = false;
        try {
            Response impact = impact(PlatformTokens.operator(), tenant, basic);

            assertThat(impact.status()).isEqualTo(503);
            assertThat(impact.code()).isEqualTo("plan.catalogUnavailable");
            assertThat(impact.body().get("data")).isNull();
            assertThat(IamPlanImpactFixture.received(tenant)).isNull();
        } finally {
            PlatformTokens.catalogAvailable = true;
        }
    }

    @Test
    void upgradeDoesNotDependOnTheCatalog() throws Exception {
        UUID basic = plan(List.of("order:read"), List.of(), 3);
        UUID pro = plan(List.of("order:read", "order:approve"), List.of(REGISTERED), 50);
        UUID tenant = tenant(basic, null);
        PlatformTokens.catalogAvailable = false;
        try {
            Response impact = impact(PlatformTokens.operator(), tenant, pro);

            assertThat(impact.status()).isEqualTo(200);
            assertEmpty(impact, 50);
        } finally {
            PlatformTokens.catalogAvailable = true;
        }
    }

    @Test
    void permissionsMatchChangingThePlan() throws Exception {
        UUID pro = plan(List.of("order:read", "order:approve"), List.of(), 50);
        UUID basic = plan(List.of("order:read"), List.of(), 3);
        UUID east = tenant(pro, "east");
        UUID west = tenant(pro, "west");
        IamPlanImpactFixture.stub(east, 1, 1);

        String viewer = PlatformTokens.operator(UUID.randomUUID(), List.of(READ), "east", List.of(REGION_POLICY));
        assertThat(impact(viewer, east, basic).status()).isEqualTo(403);

        String manager =
                PlatformTokens.operator(UUID.randomUUID(), List.of(READ, MANAGE), "east", List.of(REGION_POLICY));
        assertThat(impact(manager, east, basic).status()).isEqualTo(200);
        // 范围外的租户与不存在的一样，也不向 iam 查询
        assertThat(impact(manager, west, basic).code()).isEqualTo("tenant.notFound");
        assertThat(IamPlanImpactFixture.received(west)).isNull();
    }

    @Test
    void rejectsUnknownPlansAndTenantsAndAMissingPlan() throws Exception {
        UUID basic = plan(List.of("order:read"), List.of(), 3);
        UUID tenant = tenant(basic, null);
        String admin = PlatformTokens.operator();

        assertThat(impact(admin, tenant, UUID.randomUUID()).code()).isEqualTo("plan.notFound");
        assertThat(impact(admin, UUID.randomUUID(), basic).code()).isEqualTo("tenant.notFound");
        Response missing = call(admin, "GET", "/tenants/" + tenant + "/plan/impact", null);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo("request.malformed");
    }

    private static void assertEmpty(Response impact, int memberQuota) {
        assertThat(impact.data())
                .containsEntry("lostPermissions", List.of())
                .containsEntry("lostSubApps", List.of())
                .containsEntry("memberCount", null)
                .containsEntry("memberQuota", memberQuota)
                .containsEntry("membersOverQuota", 0)
                .containsEntry("affectedMemberships", 0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> lostSubApps(Response impact) {
        return (List<String>) impact.data().get("lostSubApps");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tenantOf(Response response) {
        return (Map<String, Object>) response.data().get("tenant");
    }

    private UUID plan(List<String> permissions, List<String> subApps, Integer memberQuota) throws Exception {
        var body = new HashMap<String, Object>();
        String code = "impact-" + UUID.randomUUID().toString().substring(0, 8);
        body.put("code", code);
        body.put("name", code);
        body.put("permissions", permissions);
        body.put("subApps", subApps);
        body.put("memberQuota", memberQuota);
        Response created = call(PlatformTokens.operator(), "POST", "/plans", body);
        assertThat(created.status()).isEqualTo(201);
        return UUID.fromString((String) created.data().get("id"));
    }

    private UUID tenant(UUID planId, String region) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("name", "预览租户");
        body.put("adminContact", "impact-owner@example.com");
        body.put("planId", planId.toString());
        body.put("region", region);
        Response created = call(PlatformTokens.operator(), "POST", "/tenants", body);
        assertThat(created.status()).isEqualTo(201);
        return UUID.fromString((String) tenantOf(created).get("id"));
    }

    private Response impact(String token, UUID tenantId, UUID planId) {
        return call(token, "GET", "/tenants/" + tenantId + "/plan/impact?planId=" + planId, null);
    }

    private Response call(String token, String method, String path, Object body) {
        var request = RestClient.create("http://localhost:" + port)
                .method(HttpMethod.valueOf(method))
                .uri(path)
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange(
                (req, response) -> new Response(response.getStatusCode().value(), response.bodyTo(ENVELOPE)));
    }

    record Response(int status, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }

        String code() {
            return (String) body.get("code");
        }
    }
}
