package io.saas.forge.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.saas.forge.gateway.config.GatewayUserTokenTestConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "SAASFORGE_SECRETS_IMPORT=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.loadbalancer.cache.enabled=false",
        "saas.forge.gateway.configuration-revision=test"
})
@Import({GatewayTestDiscoveryConfiguration.class, GatewayUserTokenTestConfiguration.class})
@ActiveProfiles("gateway-test")
class GatewayProblemDetailsTest {

    private static final AtomicReference<DownstreamResponse> IAM_RESPONSE = new AtomicReference<>(
            new DownstreamResponse(200, "application/json", "iam", 0));

    private static final AtomicInteger IAM_REQUESTS = new AtomicInteger();

    private static final HttpServer IAM_SERVER = startIamServer();

    private static final HttpServer ENTITLEMENT_SERVER = startSuccessServer();

    private static final URI IAM_URI = URI.create("http://127.0.0.1:" + IAM_SERVER.getAddress().getPort());

    private static final URI ENTITLEMENT_URI = URI.create("http://127.0.0.1:" + ENTITLEMENT_SERVER.getAddress().getPort());

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void discoverServices(DynamicPropertyRegistry registry) {
        GatewayTestDiscoveryConfiguration.discoverAt(GatewayTestDiscoveryConfiguration.IAM_SERVICE_ID, IAM_URI);
        GatewayTestDiscoveryConfiguration.discoverAt(
                GatewayTestDiscoveryConfiguration.TENANT_ACCESS_SERVICE_ID, URI.create("http://127.0.0.1:1"));
        GatewayTestDiscoveryConfiguration.discoverAt(
                GatewayTestDiscoveryConfiguration.ENTITLEMENT_SERVICE_ID, ENTITLEMENT_URI);
        registry.add("spring.http.clients.read-timeout", () -> "1s");
    }

    @AfterAll
    static void stopServers() {
        IAM_SERVER.stop(0);
        ENTITLEMENT_SERVER.stop(0);
    }

    @BeforeEach
    void resetIamResponse() {
        GatewayTestDiscoveryConfiguration.discoverAt(GatewayTestDiscoveryConfiguration.IAM_SERVICE_ID, IAM_URI);
        IAM_RESPONSE.set(new DownstreamResponse(200, "application/json", "iam", 0));
        IAM_REQUESTS.set(0);
    }

    @Test
    void passesThroughAnEligibleDownstreamProblemWithoutChanges() throws IOException, InterruptedException {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String problem = "{\"type\":\"urn:saas.forge:problem:tenant-not-found\",\"title\":\"Tenant not found\","
                + "\"status\":404,\"code\":\"TENANT_NOT_FOUND\",\"detail\":\"The tenant does not exist.\","
                + "\"traceId\":\"" + traceId + "\"}";
        IAM_RESPONSE.set(new DownstreamResponse(404, "application/problem+json", problem, 0));

        HttpResponse<String> response = send(HttpRequest.newBuilder(gatewayUri("/.well-known/jwks.json"))
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .GET()
                .build());

        assertEquals(404, response.statusCode());
        assertEquals("application/problem+json", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(problem, response.body());
    }

    @Test
    void normalizesMalformedOrMismatchedDownstreamProblems() throws IOException, InterruptedException {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String traceparent = "00-" + traceId + "-00f067aa0ba902b7-01";
        for (DownstreamResponse invalid : new DownstreamResponse[] {
                new DownstreamResponse(404, "application/json", "{\"status\":404}", 0),
                new DownstreamResponse(404, "application/problem+json", "{\"type\":\"urn:saas.forge:problem:tenant-not-found\","
                        + "\"title\":\"Tenant not found\",\"status\":409,\"code\":\"TENANT_NOT_FOUND\","
                        + "\"detail\":\"The tenant does not exist.\",\"traceId\":\"" + traceId + "\"}", 0),
                new DownstreamResponse(404, "application/problem+json", "{\"type\":\"urn:saas.forge:problem:other\","
                        + "\"title\":\"Tenant not found\",\"status\":404,\"code\":\"TENANT_NOT_FOUND\","
                        + "\"detail\":\"The tenant does not exist.\",\"traceId\":\"" + traceId + "\"}", 0),
                new DownstreamResponse(404, "application/problem+json", "{\"type\":\"urn:saas.forge:problem:tenant-not-found\","
                        + "\"title\":\"Tenant not found\",\"status\":404,\"code\":\"TENANT_NOT_FOUND\","
                        + "\"detail\":\"The tenant does not exist.\",\"traceId\":\"00000000000000000000000000000001\"}", 0)
        }) {
            IAM_RESPONSE.set(invalid);
            var response = send(HttpRequest.newBuilder(gatewayUri("/.well-known/jwks.json"))
                    .header("traceparent", traceparent)
                    .header("Origin", "https://console.saas.forge.test")
                    .GET()
                    .build());
            assertGatewayProblem(response, 502, "UPSTREAM_INVALID_RESPONSE", traceId);
            assertEquals("https://console.saas.forge.test",
                    response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
            assertTrue(response.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());
        }
    }

    @Test
    void mapsTheConfiguredUpstreamReadTimeoutWithoutRetrying() throws IOException, InterruptedException {
        IAM_RESPONSE.set(new DownstreamResponse(200, "application/json", "late", 2000));

        HttpResponse<String> response = send(HttpRequest.newBuilder(gatewayUri("/.well-known/jwks.json"))
                .header("Origin", "https://console.saas.forge.test").GET().build());

        assertGatewayProblem(response, 504, "UPSTREAM_TIMEOUT", null);
        assertEquals("https://console.saas.forge.test",
                response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertEquals(1, IAM_REQUESTS.get());
    }

    @Test
    void normalizesAnUpstreamConnectionFailure() throws IOException, InterruptedException {
        assertGatewayProblem(send(HttpRequest.newBuilder(gatewayUri("/api/v1/platform/tenants"))
                .header("Authorization", GatewayUserTokenTestConfiguration.VALID_BEARER)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()), 502, "UPSTREAM_INVALID_RESPONSE", null);
    }

    @Test
    void usesTheSharedProblemDetailsShapeForGatewayErrors() throws IOException, InterruptedException {
        assertGatewayProblem(send("GET", "/not-declared"), 404, "ROUTE_NOT_FOUND", null);
    }

    private void assertGatewayProblem(HttpResponse<String> response, int status, String code, String traceId) {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        assertTrue(response.body().contains("\"type\":\"urn:saas.forge:problem:" + code.toLowerCase().replace('_', '-') + "\""));
        assertTrue(response.body().contains("\"title\":"));
        assertTrue(response.body().contains("\"status\":" + status));
        assertTrue(response.body().contains("\"code\":\"" + code + "\""));
        assertTrue(response.body().contains("\"detail\":"));
        assertTrue(response.body().contains("\"traceId\":"));
        if (traceId != null) {
            assertTrue(response.body().contains("\"traceId\":\"" + traceId + "\""));
        }
    }

    private HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(gatewayUri(path)).method(method, HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI gatewayUri(String path) {
        return URI.create("http://127.0.0.1:" + gatewayPort + path);
    }

    private static HttpServer startIamServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                IAM_REQUESTS.incrementAndGet();
                DownstreamResponse response = IAM_RESPONSE.get();
                if (response.delayMillis() > 0) {
                    try {
                        Thread.sleep(response.delayMillis());
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", response.contentType());
                exchange.sendResponseHeaders(response.status(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start the gateway target test server", exception);
        }
    }

    private static HttpServer startSuccessServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = "entitlement".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start the gateway target test server", exception);
        }
    }

    private record DownstreamResponse(int status, String contentType, String body, long delayMillis) {
    }
}
