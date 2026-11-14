package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.platform.tenant.mapper.CoolingOffMapper;
import io.saasforge.platform.tenant.service.AiUsageDailyReported;
import io.saasforge.platform.tenant.service.AiUsageDailyReportedHandler;
import io.saasforge.platform.tenant.service.TenantLifecycleService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.provisioning.TenantDeleted;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;

@Sql("/fixtures/plan.sql")
class TenantAiUsageTest extends PlatformIntegrationTest {
    @Autowired
    AiUsageDailyReportedHandler reports;

    @Autowired
    ObjectProvider<TenantDataCleaner> cleaners;

    @Autowired
    CoolingOffMapper coolingOff;

    @Autowired
    TenantLifecycleService lifecycle;

    @LocalServerPort
    int port;

    @Test
    void finalTenantDeletionCleansOnlyItsProjectionAndLateSnapshotsCannotReviveIt() throws Exception {
        String admin = PlatformTokens.operator();
        UUID tenant = create(admin, "east");
        UUID other = create(admin, "west");
        LocalDate date = LocalDate.of(2026, 11, 14);
        CurrentTenant.run(tenant, () -> reports.handle(event(tenant, date, 1, 9, 2, 1, 1, 0, 0, 0)));
        CurrentTenant.run(other, () -> reports.handle(event(other, date, 1, 5, 1, 0, 1, 0, 0, 0)));
        var cleaner = cleaners.orderedStream()
                .filter(c -> c.getClass().getSimpleName().startsWith("TenantAiUsageCleaner"))
                .findFirst()
                .orElse(null);
        assertThat(cleaner).as("Platform projection lifecycle cleaner").isNotNull();
        RestClient.create("http://localhost:" + port)
                .post()
                .uri("/tenants/" + tenant + "/transitions")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "deleted"))
                .retrieve()
                .toBodilessEntity();
        coolingOff.endCoolingOff(tenant);
        lifecycle.purgeDue();
        assertThatThrownBy(() -> cleaner.clean(new TenantDeleted(tenant))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CurrentTenant.run(other, () -> cleaner.clean(new TenantDeleted(tenant))))
                .isInstanceOf(RuntimeException.class);
        CurrentTenant.run(tenant, () -> cleaner.clean(new TenantDeleted(tenant)));
        CurrentTenant.run(tenant, () -> cleaner.clean(new TenantDeleted(tenant)));
        assertThat(read(admin, tenant, 200).get("received")).isEqualTo(false);
        assertThat(read(admin, other, 200).get("totalTokens")).isEqualTo(6);
        CurrentTenant.run(tenant, () -> reports.handle(event(tenant, date, 99, 900, 100, 0, 2, 0, 0, 0)));
        assertThat(read(admin, tenant, 200).get("received")).isEqualTo(false);
    }

    @Test
    void snapshotsReplaceByVersionAndQueriesRespectOperatorTenantScope() throws Exception {
        String admin = PlatformTokens.operator();
        UUID tenant = create(admin, "east");
        UUID other = create(admin, "west");
        LocalDate date = LocalDate.of(2026, 11, 14);
        var latest = event(tenant, date, 2, 90, 20, 10, 1, 1, 1, 600);
        CurrentTenant.run(tenant, () -> reports.handle(latest));
        CurrentTenant.run(tenant, () -> reports.handle(latest));
        CurrentTenant.run(tenant, () -> reports.handle(event(tenant, date, 1, 900, 900, 0, 2, 3, 0, 0)));
        CurrentTenant.run(tenant, () -> reports.handle(event(tenant, date.plusDays(1), 1, 10, 2, 0, 1, 0, 0, 0)));
        var usage = read(admin, tenant, 200);
        assertThat(usage.get("inputTokens")).isEqualTo(100);
        assertThat(usage.get("totalTokens")).isEqualTo(122);
        assertThat(usage.get("estimatedTokens")).isEqualTo(600);
        assertThat(usage.get("unknownUsageTurns")).isEqualTo(1);
        assertThat((List<?>) usage.get("days")).hasSize(2);
        assertThat(read(admin, other, 200).get("received")).isEqualTo(false);
        String east = PlatformTokens.operator(
                UUID.randomUUID(),
                List.of("platform:tenant:read"),
                "east",
                List.of(PlatformPermissionTest.REGION_POLICY));
        read(east, tenant, 200);
        read(east, other, 404);
        read(PlatformTokens.operator(UUID.randomUUID(), List.of(), null, List.of()), tenant, 403);
        read(PlatformTokens.operator(UUID.randomUUID(), List.of("platform:tenant:read"), null, List.of()), tenant, 404);
        assertThatThrownBy(() -> reports.handle(latest)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CurrentTenant.run(other, () -> reports.handle(latest)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() ->
                        CurrentTenant.run(tenant, () -> reports.handle(event(tenant, date, 3, 1, 1, 2, 1, 0, 0, 0))))
                .isInstanceOf(RuntimeException.class);
        UUID absent = UUID.randomUUID();
        assertThatThrownBy(() ->
                        CurrentTenant.run(absent, () -> reports.handle(event(absent, date, 1, 1, 1, 0, 1, 0, 0, 0))))
                .isInstanceOf(RuntimeException.class);
        assertThat(read(admin, tenant, 200).get("totalTokens")).isEqualTo(122);
    }

    private AiUsageDailyReported event(
            UUID tenant,
            LocalDate date,
            long version,
            long input,
            long output,
            long cached,
            long turns,
            long tools,
            long unknown,
            long estimate) {
        return new AiUsageDailyReported(
                tenant,
                date,
                "Asia/Shanghai",
                version,
                input,
                output,
                cached,
                turns,
                tools,
                unknown,
                estimate,
                OffsetDateTime.now());
    }

    private UUID create(String token, String region) {
        var result = RestClient.create("http://localhost:" + port)
                .post()
                .uri("/tenants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "name",
                        "用量范围验收",
                        "adminContact",
                        "usage-" + UUID.randomUUID() + "@example.test",
                        "region",
                        region,
                        "planId",
                        PlatformTokens.DEFAULT_PLAN_ID))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return UUID.fromString((String) ((Map<?, ?>) ((Map<?, ?>) result.get("data")).get("tenant")).get("id"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String token, UUID tenant, int expected) {
        return RestClient.create("http://localhost:" + port)
                .get()
                .uri("/tenants/" + tenant + "/ai-usage?month=2026-11")
                .header("Authorization", "Bearer " + token)
                .exchange((req, res) -> {
                    assertThat(res.getStatusCode().value()).isEqualTo(expected);
                    var result = res.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {});
                    return expected == 200 ? (Map<String, Object>) result.get("data") : result;
                });
    }

    @Test
    void missingReportsAreExplicitAndTenantReadPolicyStillApplies() throws Exception {
        var client = RestClient.create("http://localhost:" + port);
        String admin = PlatformTokens.operator();
        var created = client.post()
                .uri("/tenants")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "name", "用量验收", "adminContact", "usage@example.test", "planId", PlatformTokens.DEFAULT_PLAN_ID))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        var detail = (Map<?, ?>) created.get("data");
        var tenant = (Map<?, ?>) detail.get("tenant");
        var result = client.get()
                .uri("/tenants/" + tenant.get("id") + "/ai-usage?month=2026-11")
                .header("Authorization", "Bearer " + admin)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(((Map<?, ?>) result.get("data")).get("received")).isEqualTo(false);
    }
}
