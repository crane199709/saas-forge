package io.saasforge.sdk.scheduling;

import io.saasforge.sdk.core.api.ApiResponse;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class TenantDirectoryClient {
    private static final ParameterizedTypeReference<ApiResponse<List<UUID>>> RESPONSE =
            new ParameterizedTypeReference<>() {};
    private final RestClient client;

    TenantDirectoryClient(SchedulingProperties properties) {
        properties.requireTenantTask();
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        client = RestClient.builder()
                .baseUrl(properties.iamUri())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .build();
    }

    List<UUID> tenants() {
        List<UUID> tenants = new ArrayList<>();
        for (int page = 1; ; page++) {
            ApiResponse<List<UUID>> response = client.get()
                    .uri("/internal/tenants?status=trial,active&page={page}&size=100", page)
                    .retrieve()
                    .body(RESPONSE);
            if (response == null
                    || !ApiResponse.OK.equals(response.code())
                    || response.data() == null
                    || response.meta() == null
                    || response.meta().page() == null
                    || response.meta().page().number() != page) {
                throw new IllegalStateException("IAM returned an invalid tenant directory page " + page);
            }
            tenants.addAll(response.data());
            if (page >= response.meta().page().totalPages()) return List.copyOf(tenants);
        }
    }
}
