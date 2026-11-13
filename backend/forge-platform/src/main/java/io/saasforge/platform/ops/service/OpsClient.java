package io.saasforge.platform.ops.service;

import io.saasforge.platform.ops.config.OpsProperties;
import io.saasforge.platform.ops.model.OpsServiceStatus;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 平台运维功能的服务调用入口；调用方负责 Operator 功能权限和具体操作的审计。 */
@Service
public class OpsClient {
    private static final ParameterizedTypeReference<ApiResponse<Boolean>> PROBE = new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final OpsProperties properties;
    private final OpsServiceDirectory directory;

    OpsClient(
            @Qualifier("opsRestClientBuilder") RestClient.Builder builder,
            OpsProperties properties,
            OpsServiceDirectory directory) {
        this.client = builder.build();
        this.properties = properties;
        this.directory = directory;
    }
    /** 已上报服务逐一探测；未配置令牌、未引入 SDK、发现无实例或调用失败都只影响该服务。 */
    public List<OpsServiceStatus> services() {
        return directory.services().stream()
                .map(service -> new OpsServiceStatus(service, available(service)))
                .toList();
    }
    /** 读取所属服务的运维资源，path 为 /internal/ops 下的固定相对路径，可带查询参数；保留分页与业务失败信封。 */
    public <T> ApiResponse<T> get(
            String service, String path, ParameterizedTypeReference<ApiResponse<T>> responseType) {
        return get(service, path, responseType, true);
    }

    /** 固定 IAM 管理通道：新服务登记不能依赖其已有清单；仍使用 IAM 独立 Ops Token。 */
    public <T> ApiResponse<T> iamGet(String path, ParameterizedTypeReference<ApiResponse<T>> responseType) {
        return get("forge-iam", path, responseType, false);
    }

    private <T> ApiResponse<T> get(
            String service,
            String path,
            ParameterizedTypeReference<ApiResponse<T>> responseType,
            boolean requireReported) {
        if (requireReported) {
            requireService(service);
        }
        try {
            return requireResponse(client.get()
                    .uri(uri(service, path))
                    .header(HttpHeaders.AUTHORIZATION, token(service))
                    .retrieve()
                    // 业务拒绝保留原信封；认证错误和服务错误仍按不可用处理。
                    .onStatus(
                            status -> status.is4xxClientError() && status.value() != 401 && status.value() != 403,
                            (request, response) -> {})
                    .body(responseType));
        } catch (RestClientException | IllegalStateException error) {
            throw new ForgeException(OpsErrorCode.SERVICE_UNAVAILABLE);
        }
    }
    /** 在所属服务执行运维命令；调用方在平台校验 manage 权限；结构化业务拒绝原样返回，带审计的操作须将失败转换为业务异常。 */
    public <T> ApiResponse<T> post(
            String service, String path, Object body, ParameterizedTypeReference<ApiResponse<T>> responseType) {
        return post(service, path, body, responseType, true);
    }

    /** 固定 IAM 管理通道；调用方必须校验管理权限并从 OperatorContext 取得 actor。 */
    public <T> ApiResponse<T> iamPost(
            String path, Object body, ParameterizedTypeReference<ApiResponse<T>> responseType) {
        return post("forge-iam", path, body, responseType, false);
    }

    private <T> ApiResponse<T> post(
            String service,
            String path,
            Object body,
            ParameterizedTypeReference<ApiResponse<T>> responseType,
            boolean requireReported) {
        if (requireReported) {
            requireService(service);
        }
        try {
            return requireResponse(client.post()
                    .uri(uri(service, path))
                    .header(HttpHeaders.AUTHORIZATION, token(service))
                    .body(body)
                    .retrieve()
                    // 业务拒绝保留原信封；认证错误和服务错误仍按不可用处理。
                    .onStatus(
                            status -> status.is4xxClientError() && status.value() != 401 && status.value() != 403,
                            (request, response) -> {})
                    .body(responseType));
        } catch (RestClientException | IllegalStateException error) {
            throw new ForgeException(OpsErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private boolean available(String service) {
        if (!properties.tokens().containsKey(service)) {
            return false;
        }
        try {
            var response = client.get()
                    .uri(uri(service, ""))
                    .header(HttpHeaders.AUTHORIZATION, token(service))
                    .retrieve()
                    .body(PROBE);
            return response != null && Boolean.TRUE.equals(response.data());
        } catch (RestClientException | IllegalStateException error) {
            return false;
        }
    }

    private void requireService(String service) {
        if (!directory.services().contains(service)) {
            throw new ForgeException(OpsErrorCode.SERVICE_NOT_FOUND);
        }
    }

    private String token(String service) {
        String token = properties.tokens().get(service);
        if (token == null) {
            throw new ForgeException(OpsErrorCode.SERVICE_UNAVAILABLE);
        }
        return "Bearer " + token;
    }

    private static URI uri(String service, String path) {
        if ((!path.isEmpty() && !path.startsWith("/")) || path.contains("..") || path.contains("#")) {
            throw new IllegalArgumentException("Ops path must be relative to /internal/ops");
        }
        // 调用方已按路径段编码任务名，使用 URI 重载避免 RestClient 再次编码百分号。
        return URI.create("lb://" + service + "/internal/ops" + path);
    }

    private static <T> ApiResponse<T> requireResponse(ApiResponse<T> response) {
        if (response == null) {
            throw new ForgeException(OpsErrorCode.SERVICE_UNAVAILABLE);
        }
        return response;
    }
}
