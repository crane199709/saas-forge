package io.saasforge.platform.onboarding.service;

import io.saasforge.platform.onboarding.config.OnboardingProperties;
import io.saasforge.platform.onboarding.model.OnboardingDiagnostics;
import io.saasforge.platform.onboarding.model.OnboardingDiagnostics.RequestEvidence;
import io.saasforge.platform.onboarding.model.OnboardingDiagnostics.RuntimeEvidence;
import io.saasforge.platform.onboarding.model.OnboardingDiagnostics.ServiceDiagnostic;
import io.saasforge.platform.ops.config.OpsProperties;
import io.saasforge.platform.ops.service.OpsServiceDirectory;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.manifest.OnboardingStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/** 当次只读聚合；任何服务或配置源失败都不掩盖其他证据。 */
@Service
public class OnboardingService implements AutoCloseable {
    private static final ParameterizedTypeReference<ApiResponse<OnboardingStatus>> STATUS =
            new ParameterizedTypeReference<>() {};
    private final RestClient ops;
    private final RestClient gateway;
    private final OpsProperties tokens;
    private final OpsServiceDirectory directory;
    private final GatewayDeclarationSource source;
    private final ExecutorService probes = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128),
            task -> {
                var t = new Thread(task, "onboarding-probe");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public OnboardingService(
            @Qualifier("opsRestClientBuilder") RestClient.Builder builder,
            OpsProperties tokens,
            OpsServiceDirectory directory,
            GatewayDeclarationSource source,
            OnboardingProperties properties) {
        this.ops = builder.build();
        this.tokens = tokens;
        this.directory = directory;
        this.source = source;
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        gateway = RestClient.builder()
                .baseUrl(properties.gatewayUri().toString())
                .requestFactory(factory)
                .build();
    }

    public OnboardingDiagnostics read() {
        var config = source.read();
        Set<String> candidates = new TreeSet<>(tokens.tokens().keySet());
        candidates.addAll(config.entries().keySet());
        boolean directoryAvailable = true;
        try {
            candidates.addAll(directory.services());
        } catch (RuntimeException error) {
            directoryAvailable = false;
        }
        // 逻辑清单名不一定有运行实例；保留候选，但绝不把目录存在当作加载证据。
        List<CompletableFuture<ServiceDiagnostic>> tasks = new ArrayList<>();
        for (String service : candidates) {
            if (!service.matches("[a-z0-9][a-z0-9-]*")) continue;
            var evidence = config.forService(service);
            var unavailable = new ServiceDiagnostic(
                    service,
                    evidence,
                    new RuntimeEvidence("UNAVAILABLE", Instant.now(), null),
                    new RequestEvidence("NOT_CHECKED", null, "PROBE_BUSY"));
            try {
                tasks.add(CompletableFuture.supplyAsync(
                                () -> new ServiceDiagnostic(
                                        service, evidence, runtime(service), request(service, evidence.prefix())),
                                probes)
                        .completeOnTimeout(unavailable, 12, TimeUnit.SECONDS));
            } catch (RejectedExecutionException error) {
                tasks.add(CompletableFuture.completedFuture(unavailable));
            }
        }
        return new OnboardingDiagnostics(
                directoryAvailable, tasks.stream().map(CompletableFuture::join).toList());
    }

    private RuntimeEvidence runtime(String service) {
        Instant at = Instant.now();
        String token = tokens.tokens().get(service);
        if (token == null) return new RuntimeEvidence("OPS_TOKEN_MISSING", at, null);
        try {
            var response = ops.get()
                    .uri(URI.create("lb://" + service + "/internal/onboarding-status"))
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(STATUS);
            if (response == null
                    || !"ok".equals(response.code())
                    || response.data() == null
                    || response.data().phase() == null) throw new IllegalStateException();
            return new RuntimeEvidence("LOADED", at, response.data());
        } catch (RestClientResponseException error) {
            String status = switch (error.getStatusCode().value()) {
                case 404 -> "UNSUPPORTED";
                case 401, 403 -> "OPS_CREDENTIAL_REJECTED";
                default -> "UNAVAILABLE";
            };
            return new RuntimeEvidence(status, at, null);
        } catch (RuntimeException error) {
            return new RuntimeEvidence("UNAVAILABLE", at, null);
        }
    }

    private RequestEvidence request(String service, String prefix) {
        if (prefix == null) return new RequestEvidence("NOT_CHECKED", null, "ROUTE_PREFIX_MISSING");
        Instant at = Instant.now();
        try {
            return gateway.get().uri(prefix + "/v3/api-docs").exchange((q, response) -> {
                if (response.getStatusCode().value() != 200) return new RequestEvidence("FAILED", at, "HTTP_REJECTED");
                MediaType type = response.getHeaders().getContentType();
                if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type))
                    return new RequestEvidence("FAILED", at, "HTML_OR_INVALID_OPENAPI");
                var json = JsonMapper.builder().build().readTree(response.getBody());
                if (json == null
                        || !json.path("openapi").asString("").startsWith("3.")
                        || !json.path("info").isObject()
                        || json.path("info").path("title").asString("").isBlank()
                        || json.path("info").path("version").asString("").isBlank()
                        || !json.path("paths").isObject())
                    return new RequestEvidence("FAILED", at, "HTML_OR_INVALID_OPENAPI");
                if (!service.equals(json.path("x-forge-service").asString("")))
                    return new RequestEvidence("FAILED", at, "SERVICE_IDENTITY_MISMATCH");
                return new RequestEvidence("VERIFIED", at, null);
            });
        } catch (RuntimeException error) {
            return new RequestEvidence("FAILED", at, "REQUEST_FAILED");
        }
    }

    @Override
    public void close() {
        probes.shutdownNow();
    }
}
