package io.saasforge.sdk.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        classes = ServiceIdentityHttpTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.application.name=demo-service")
class ServiceIdentityHttpTest {
    @LocalServerPort
    int port;

    @Test
    void openApiIdentifiesActualService() {
        assertThat(RestClient.create("http://localhost:" + port)
                        .get()
                        .uri("/v3/api-docs")
                        .retrieve()
                        .body(String.class))
                .contains("\"x-forge-service\":\"demo-service\"");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {}
}
