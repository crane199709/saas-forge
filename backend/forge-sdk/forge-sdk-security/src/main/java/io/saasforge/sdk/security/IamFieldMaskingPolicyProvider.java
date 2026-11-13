package io.saasforge.sdk.security;

import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 服务范围完全由 IAM 对凭证的认证决定，请求不包含可自选的 service 参数。 */
final class IamFieldMaskingPolicyProvider implements FieldMaskingPolicyProvider {
    private static final ParameterizedTypeReference<ApiResponse<Map<String, EffectiveFieldPolicy>>> RESPONSE =
            new ParameterizedTypeReference<>() {};
    private static final Set<String> GRADES = Set.of("L1", "L2", "L3", "L4");
    private final RestClient client;
    private final MaskingEngine engine = new MaskingEngine();

    IamFieldMaskingPolicyProvider(FieldMaskingProperties properties) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .baseUrl(properties.iamUri())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .build();
    }

    @Override
    public Map<String, EffectiveFieldPolicy> policies(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        try {
            var response = client.get()
                    .uri("/internal/field-masking/{tenantId}", tenantId)
                    .retrieve()
                    .body(RESPONSE);
            if (response == null || !ApiResponse.OK.equals(response.code()) || response.data() == null) {
                throw unavailable();
            }
            var policies = Map.copyOf(response.data());
            for (var entry : policies.entrySet()) {
                var field = entry.getValue();
                if (!entry.getKey().equals(field.path())
                        || !GRADES.contains(field.grade())
                        || ("L4".equals(field.grade()) && field.exportAllowed())) {
                    throw unavailable();
                }
                // null 规则保留已声明字段的失败语义；执行通道不能把它视为未声明字段。
                if (field.rule() != null) {
                    engine.validate(field.rule());
                }
            }
            return policies;
        } catch (RestClientException | IllegalArgumentException | NullPointerException | ForgeException error) {
            // HTTP 响应体及嵌套异常可能包含原文，不保留 cause 或对方错误消息。
            throw unavailable();
        }
    }

    private static ForgeException unavailable() {
        return ForgeException.withErrorMessage(
                FrameworkErrorCode.SYSTEM_UNAVAILABLE, "Field masking policy is unavailable");
    }
}
