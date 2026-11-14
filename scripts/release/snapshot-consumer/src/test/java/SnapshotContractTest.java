import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SnapshotContractTest {
    @Test
    void aiConsumerCanReadThePublishedPlanContract() {
        var json = JsonMapper.builder().build();
        var event = new TenantPlanChanged(UUID.randomUUID(), UUID.randomUUID(), "ai",
                List.of("ai:conversation:use"), List.of("ai"), null, null, 1, 5_000_000_000L);
        assertEquals(event, json.readValue(json.writeValueAsString(event), TenantPlanChanged.class));
        assertNull(json.readValue("{\"planCode\":\"legacy\",\"version\":1}", TenantPlanChanged.class).tokenQuota());
    }

    @Test
    void aiManifestCanDeclareUseAndVersionActions() {
        var json = JsonMapper.builder().build();
        ResourceManifest manifest = json.readValue("""
                {"service":"ai-service","version":"1","permissions":[
                  {"code":"ai:conversation:use","name":"使用 AI 助手"},
                  {"code":"ai:prompt:publish","name":"发布 Prompt"},
                  {"code":"ai:prompt:rollback","name":"回滚 Prompt"}],
                 "menus":[{"code":"ai.workbench","name":"AI 工作台","app":"ai","path":"conversations",
                   "permission":"ai:conversation:use"}]}
                """, ResourceManifest.class);
        manifest.validate();
        assertEquals("ai", manifest.menus().get(0).app());
    }
}
