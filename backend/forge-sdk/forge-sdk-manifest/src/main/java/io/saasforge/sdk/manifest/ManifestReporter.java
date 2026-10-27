package io.saasforge.sdk.manifest;

import io.saasforge.sdk.core.ResourceManifest;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 把 Resource Manifest 上报到 iam 的 {@code POST /internal/manifests}，以服务专属的上报令牌认证。
 *
 * <p>iam 按 {@code service + version} 存储：同一版本重复上报相同内容是无操作，内容不同则以 409 拒绝。
 */
public final class ManifestReporter {
    private final RestClient client;

    /**
     * 创建上报器。
     *
     * @param iamUri iam 的内部地址，不经网关
     * @param token 本服务的上报令牌，须与 iam 为该服务配置的一致
     */
    public ManifestReporter(String iamUri, String token) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .baseUrl(iamUri)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    /**
     * 上报清单。
     *
     * @param manifest 待上报的清单
     * @throws IllegalStateException iam 拒绝上报，消息中带 HTTP 状态码与响应信封
     */
    public void report(ResourceManifest manifest) {
        try {
            client.post()
                    .uri("/internal/manifests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(manifest)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException error) {
            throw new IllegalStateException(
                    "IAM rejected manifest " + manifest.service() + "@" + manifest.version() + ": HTTP "
                            + error.getStatusCode().value() + " " + error.getResponseBodyAsString(),
                    error);
        }
    }
}
