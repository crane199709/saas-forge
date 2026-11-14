package io.saasforge.sdk.provisioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TenantPlanChangedTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void oldMessagesDefaultToUnlimitedTokens() {
        var event =
                new TenantPlanChanged(UUID.randomUUID(), UUID.randomUUID(), "basic", List.of(), List.of(), 5, null, 1);
        String payload = JSON.writeValueAsString(new LegacyPlanChanged(
                event.tenantId(),
                event.planId(),
                event.planCode(),
                event.permissions(),
                event.subApps(),
                event.memberQuota(),
                event.storageQuotaMb(),
                event.version()));

        assertEquals(event, JSON.readValue(payload, TenantPlanChanged.class));
        assertNull(event.tokenQuota());
    }

    @Test
    void newMessagesRemainReadableByExistingSubscribers() {
        var event = new TenantPlanChanged(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ai",
                List.of("ai:conversation:use"),
                List.of("ai"),
                5,
                null,
                2,
                5_000_000_000L);
        String payload = JSON.writeValueAsString(event);

        assertEquals(event, JSON.readValue(payload, TenantPlanChanged.class));
        assertEquals(
                new LegacyPlanChanged(
                        event.tenantId(),
                        event.planId(),
                        "ai",
                        List.of("ai:conversation:use"),
                        List.of("ai"),
                        5,
                        null,
                        2),
                JSON.readValue(payload, LegacyPlanChanged.class));
    }

    // 升级前的消息结构与 MQ 使用相同的默认 JsonMapper，验证滚动升级期间的消费兼容性。
    record LegacyPlanChanged(
            UUID tenantId,
            UUID planId,
            String planCode,
            List<String> permissions,
            List<String> subApps,
            Integer memberQuota,
            Long storageQuotaMb,
            long version) {}
}
