package io.saasforge.sdk.manifest;

import io.saasforge.sdk.core.ResourceManifest;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

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
     * @throws IllegalArgumentException 清单结构非法，上报前拒绝
     * @throws ManifestReportException 结构化上报失败，不包含原始响应或内部地址
     */
    public void report(ResourceManifest manifest) {
        manifest.validate();
        try {
            var response = client.post()
                    .uri("/internal/manifests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(manifest)
                    .retrieve()
                    .toEntity(String.class);
            if (response.getStatusCode().value() != 200
                    && response.getStatusCode().value() != 201) {
                throw new ManifestReportException(ManifestFailure.UNEXPECTED_RESPONSE);
            }
            try {
                var body = JsonMapper.builder().build().readTree(response.getBody());
                if (body == null || !"ok".equals(body.path("code").asString(""))) {
                    throw new ManifestReportException(ManifestFailure.UNEXPECTED_RESPONSE);
                }
            } catch (RuntimeException invalid) {
                throw new ManifestReportException(ManifestFailure.UNEXPECTED_RESPONSE);
            }
        } catch (RestClientResponseException error) {
            String code = "";
            try {
                code = JsonMapper.builder()
                        .build()
                        .readTree(error.getResponseBodyAsString())
                        .path("code")
                        .asString();
            } catch (RuntimeException ignored) {
                /* 非信封响应仍按 HTTP 分类，禁止保留原文。 */
            }
            ManifestFailure reason = switch (error.getStatusCode().value()) {
                case 401 -> ManifestFailure.CREDENTIAL_REJECTED;
                case 403 ->
                    "manifest.serviceMismatch".equals(code)
                            ? ManifestFailure.SERVICE_MISMATCH
                            : ManifestFailure.HTTP_REJECTED;
                case 409 ->
                    "manifest.versionConflict".equals(code)
                            ? ManifestFailure.VERSION_CONFLICT
                            : ManifestFailure.HTTP_REJECTED;
                case 400 -> ManifestFailure.INVALID_MANIFEST;
                default -> ManifestFailure.HTTP_REJECTED;
            };
            throw new ManifestReportException(reason);
        } catch (ResourceAccessException error) {
            throw new ManifestReportException(ManifestFailure.NETWORK_UNREACHABLE);
        }
    }
}
