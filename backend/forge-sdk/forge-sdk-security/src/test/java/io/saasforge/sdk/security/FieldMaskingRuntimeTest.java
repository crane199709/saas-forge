package io.saasforge.sdk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.saasforge.sdk.core.api.ForgeException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JacksonModule;

/** 以 IAM HTTP 边界替身验证真实客户端、自动配置与启动要求。 */
class FieldMaskingRuntimeTest {
    static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    final AtomicReference<String> strategy = new AtomicReference<>("phone");
    HttpServer iam;

    @BeforeEach
    void start() throws Exception {
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/field-masking/" + TENANT, exchange -> {
            boolean authorized = "Bearer runtime-test-token"
                    .equals(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (authorized
                            ? "{\"code\":\"ok\",\"httpStatus\":200,\"data\":{\"person.phone\":{\"path\":\"person.phone\",\"grade\":\"L3\",\"rule\":{\"strategy\":\""
                                    + strategy.get() + "\"},\"exportAllowed\":true}}}"
                            : "{\"code\":\"private-response-text\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(authorized ? 200 : 401, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        iam.start();
    }

    @AfterEach
    void stop() {
        iam.stop(0);
    }

    @Test
    void readsCurrentPoliciesWithoutServingStalePlatformChanges() {
        enabled().run(context -> {
            var provider = context.getBean(FieldMaskingPolicyProvider.class);
            var first = provider.policies(TENANT);
            assertThat(first.get("person.phone").rule().strategy()).isEqualTo("phone");
            assertThatThrownBy(() -> first.clear()).isInstanceOf(UnsupportedOperationException.class);
            strategy.set("name");
            assertThat(provider.policies(TENANT).get("person.phone").rule().strategy())
                    .isEqualTo("name");
        });
    }

    @Test
    void deniedOrInvalidRuntimeConfigurationFailsWithoutResponseBodyLeak() {
        enabled().withPropertyValues("forge.field-masking.token=wrong-token").run(context -> {
            assertThatThrownBy(() ->
                            context.getBean(FieldMaskingPolicyProvider.class).policies(TENANT))
                    .isInstanceOf(ForgeException.class)
                    .hasMessageNotContaining("private-response-text")
                    .hasNoCause();
        });
        strategy.set("regex");
        enabled()
                .run(context -> assertThatThrownBy(() -> context.getBean(FieldMaskingPolicyProvider.class)
                                .policies(TENANT))
                        .isInstanceOf(ForgeException.class));
    }

    @Test
    void legacyServicesNeedNoConfigurationAndEnabledRuntimeRequiresUriAndCredential() {
        base().run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FieldMaskingPolicyProvider.class));
        base().withPropertyValues("forge.field-masking.enabled=true", "forge.field-masking.token=secret")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("forge.field-masking.iam-uri"));
        base().withPropertyValues("forge.field-masking.enabled=true", "forge.field-masking.iam-uri=http://127.0.0.1")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("forge.field-masking.token"));
    }

    @Test
    void jacksonModuleDoesNotRequireTheOptionalMvcRuntime() {
        enabled()
                .withConfiguration(AutoConfigurations.of(FieldMaskingJacksonAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("org.springframework.web.servlet"))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JacksonModule.class));
    }

    ApplicationContextRunner base() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FieldMaskingAutoConfiguration.class));
    }

    ApplicationContextRunner enabled() {
        return base().withPropertyValues(
                        "forge.field-masking.enabled=true",
                        "forge.field-masking.iam-uri=http://127.0.0.1:"
                                + iam.getAddress().getPort(),
                        "forge.field-masking.token=runtime-test-token");
    }
}
