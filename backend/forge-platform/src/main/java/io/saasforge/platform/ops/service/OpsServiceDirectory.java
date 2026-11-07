package io.saasforge.platform.ops.service;

import io.saasforge.platform.plan.config.IamCatalogProperties;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 以 IAM 当前已上报的 Resource Manifest 为服务目录，不把配置了令牌的服务视为已上线。 */
@Service
public class OpsServiceDirectory {
    private static final ParameterizedTypeReference<ApiResponse<List<String>>> RESPONSE =
            new ParameterizedTypeReference<>() {};
    private final RestClient client;

    OpsServiceDirectory(IamCatalogProperties properties) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder()
                .baseUrl(properties.uri())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .build();
    }

    public List<String> services() {
        try {
            var response = client.get().uri("/internal/services").retrieve().body(RESPONSE);
            if (response == null || response.data() == null) throw new ForgeException(OpsErrorCode.CATALOG_UNAVAILABLE);
            return response.data();
        } catch (RestClientException error) {
            throw new ForgeException(OpsErrorCode.CATALOG_UNAVAILABLE);
        }
    }
}
