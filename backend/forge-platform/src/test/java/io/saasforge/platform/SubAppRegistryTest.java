package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.platform.tenant.mapper.OutboxAuditMapper;
import io.saasforge.sdk.provisioning.SubAppChanged;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Operator 维护 App Registry，每次变更经 Outbox 广播 SubAppChanged（工单 21）。 */
class SubAppRegistryTest extends PlatformIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE = new ParameterizedTypeReference<>() {};

    @LocalServerPort
    int port;

    @Autowired
    OutboxAuditMapper outbox;

    @Test
    void registryChangesAreBroadcastWithIncreasingRevisions() {
        Response created = call(
                "POST",
                "/sub-apps",
                Map.of(
                        "code", "billing",
                        "name", "账单",
                        "version", "1.0.0",
                        "remoteEntries",
                                Map.of(
                                        "local", "http://127.0.0.1:5175/remoteEntry.js",
                                        "prod", "https://cdn.example.com/billing/1.0.0/remoteEntry.js"),
                        "sharedRequirements", Map.of("vue", "3.5.0", "@crane199709/sdk", "0.1.0")));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data())
                .containsEntry("code", "billing")
                .containsEntry("version", "1.0.0")
                .containsEntry(
                        "remoteEntries",
                        Map.of(
                                "local", "http://127.0.0.1:5175/remoteEntry.js",
                                "prod", "https://cdn.example.com/billing/1.0.0/remoteEntry.js"))
                .containsEntry("sharedRequirements", Map.of("vue", "3.5.0", "@crane199709/sdk", "0.1.0"));
        String id = (String) created.data().get("id");
        SubAppChanged first = last();
        assertThat(first.code()).isEqualTo("billing");
        assertThat(first.removed()).isFalse();
        assertThat(first.remoteEntries()).containsEntry("local", "http://127.0.0.1:5175/remoteEntry.js");

        // 编码冲突与非法内容被拒绝
        assertThat(call(
                                "POST",
                                "/sub-apps",
                                Map.of(
                                        "code", "billing",
                                        "name", "x",
                                        "version", "1",
                                        "remoteEntries", Map.of("local", "http://x/remoteEntry.js"),
                                        "sharedRequirements", Map.of()))
                        .code())
                .isEqualTo("subApp.codeTaken");
        for (var invalid : List.of(
                Map.of("local", "ftp://x/remoteEntry.js"), Map.of("Bad Env", "http://x/remoteEntry.js"), Map.of())) {
            assertThat(call(
                                    "POST",
                                    "/sub-apps",
                                    Map.of(
                                            "code", "other",
                                            "name", "x",
                                            "version", "1",
                                            "remoteEntries", invalid,
                                            "sharedRequirements", Map.of()))
                            .status())
                    .isEqualTo(400);
        }
        assertThat(call(
                                "POST",
                                "/sub-apps",
                                Map.of(
                                        "code", "other",
                                        "name", "x",
                                        "version", "1",
                                        "remoteEntries", Map.of("local", "http://x/remoteEntry.js"),
                                        "sharedRequirements", Map.of("vue", "3.5")))
                        .status())
                .isEqualTo(400);

        // 修改入口地址即切换版本：整体替换，序号递增
        Response updated = call(
                "PUT",
                "/sub-apps/" + id,
                Map.of(
                        "name",
                        "账单",
                        "version",
                        "2.0.0",
                        "remoteEntries",
                        Map.of("local", "http://127.0.0.1:5176/remoteEntry.js"),
                        "sharedRequirements",
                        Map.of("vue", "3.5.0")));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.data())
                .containsEntry("remoteEntries", Map.of("local", "http://127.0.0.1:5176/remoteEntry.js"))
                .containsEntry("sharedRequirements", Map.of("vue", "3.5.0"));
        SubAppChanged second = last();
        assertThat(second.revision()).isGreaterThan(first.revision());
        assertThat(second.version()).isEqualTo("2.0.0");
        assertThat(second.remoteEntries()).isEqualTo(Map.of("local", "http://127.0.0.1:5176/remoteEntry.js"));

        // 删除后广播 removed，可以同一编码重新登记，序号继续递增
        assertThat(call("DELETE", "/sub-apps/" + id, null).status()).isEqualTo(200);
        SubAppChanged removed = last();
        assertThat(removed.removed()).isTrue();
        assertThat(removed.code()).isEqualTo("billing");
        assertThat(removed.revision()).isGreaterThan(second.revision());
        assertThat(call("GET", "/sub-apps", null).list())
                .extracting(item -> item.get("code"))
                .doesNotContain("billing");
        assertThat(call("DELETE", "/sub-apps/" + id, null).code()).isEqualTo("subApp.notFound");
        assertThat(call(
                                "POST",
                                "/sub-apps",
                                Map.of(
                                        "code", "billing",
                                        "name", "账单",
                                        "version", "3.0.0",
                                        "remoteEntries", Map.of("local", "http://127.0.0.1:5175/remoteEntry.js"),
                                        "sharedRequirements", Map.of()))
                        .status())
                .isEqualTo(201);
        assertThat(last().revision()).isGreaterThan(removed.revision());
        assertThat(call(
                                "PUT",
                                "/sub-apps/" + UUID.randomUUID(),
                                Map.of(
                                        "name",
                                        "x",
                                        "version",
                                        "1",
                                        "remoteEntries",
                                        Map.of("local", "http://x/remoteEntry.js"),
                                        "sharedRequirements",
                                        Map.of()))
                        .code())
                .isEqualTo("subApp.notFound");
    }

    private SubAppChanged last() {
        List<String> payloads = outbox.globalPayloads(SubAppChanged.class.getSimpleName());
        return JSON.readValue(payloads.get(payloads.size() - 1), SubAppChanged.class);
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
