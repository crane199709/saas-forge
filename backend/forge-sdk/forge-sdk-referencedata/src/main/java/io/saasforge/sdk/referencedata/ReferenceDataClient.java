package io.saasforge.sdk.referencedata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 从 iam {@code GET /internal/reference-data/{tenantId}} 读取某租户合并后的生效字典与本服务的参数。 */
final class ReferenceDataClient {
    private static final ParameterizedTypeReference<ApiResponse<Response>> RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;

    ReferenceDataClient(ReferenceDataProperties properties) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .baseUrl(properties.iamUri())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .build();
    }

    /**
     * @throws ForgeException 503 {@code system.unavailable} iam 不可用或拒绝请求：读不到参考数据时业务请求无法正确处理
     */
    Snapshot load(UUID tenantId) {
        try {
            ApiResponse<Response> response = client.get()
                    .uri("/internal/reference-data/{tenantId}", tenantId)
                    .retrieve()
                    .body(RESPONSE);
            if (response == null || response.data() == null) {
                throw ForgeException.withErrorMessage(
                        FrameworkErrorCode.SYSTEM_UNAVAILABLE, "IAM returned no reference data");
            }
            return response.data().snapshot();
        } catch (RestClientException error) {
            throw ForgeException.withErrorMessage(
                    FrameworkErrorCode.SYSTEM_UNAVAILABLE,
                    "Failed to read reference data of tenant " + tenantId + ": " + error.getMessage());
        }
    }

    /** 一个租户的参考数据：字典按「服务:编码」索引，参数只有本服务的，按编码索引。 */
    record Snapshot(Map<String, List<TenantDictionaryItem>> dictionaries, Map<String, Parameter> parameters) {}

    /** 生效的参数取值，JSON 写法同 Resource Manifest。 */
    record Parameter(String type, Object value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<DictionaryView> dictionaries, List<ParameterView> parameters) {
        Snapshot snapshot() {
            Map<String, List<TenantDictionaryItem>> byCode = new HashMap<>();
            for (DictionaryView dictionary : dictionaries) {
                byCode.put(dictionary.service() + ":" + dictionary.code(), List.copyOf(dictionary.items()));
            }
            Map<String, Parameter> byName = new HashMap<>();
            for (ParameterView parameter : parameters) {
                byName.put(parameter.code(), new Parameter(parameter.type(), parameter.value()));
            }
            return new Snapshot(Map.copyOf(byCode), Map.copyOf(byName));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DictionaryView(String service, String code, List<TenantDictionaryItem> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParameterView(String code, String type, Object value) {}
}
