package io.saasforge.sdk.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class ManifestReportingTest {
    @Test
    void failedStartupReportRetriesThenStopsAfterBackgroundLimit() throws Exception {
        var count = new AtomicInteger();
        var iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/manifests", exchange -> {
            count.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        iam.start();
        try (var reporting = new ManifestReporting(
                true,
                new ManifestReporter("http://127.0.0.1:" + iam.getAddress().getPort(), "secret"),
                Duration.ofMillis(5),
                Duration.ofMillis(5),
                2)) {
            reporting.start(ManifestReporterTest.MANIFEST);
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .until(() -> reporting.status().phase().equals("EXHAUSTED"));
            assertThat(count.get()).isEqualTo(6);
            assertThat(reporting.status().reportedVersion()).isNull();
            assertThat(reporting.status().lastFailure()).isEqualTo(ManifestFailure.CREDENTIAL_REJECTED);
        } finally {
            iam.stop(0);
        }
    }

    @Test
    void backgroundSuccessPreservesLastFailureAndStopsReporting() throws Exception {
        var count = new AtomicInteger();
        var iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/manifests", exchange -> {
            byte[] body = "{\"code\":\"ok\",\"httpStatus\":201}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(count.incrementAndGet() <= 4 ? 401 : 201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        iam.start();
        try (var reporting = new ManifestReporting(
                true,
                new ManifestReporter("http://127.0.0.1:" + iam.getAddress().getPort(), "secret"),
                Duration.ofMillis(5),
                Duration.ofMillis(5),
                2)) {
            reporting.start(ManifestReporterTest.MANIFEST);
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .until(() -> reporting.status().phase().equals("REPORTED"));
            assertThat(count.get()).isEqualTo(5);
            assertThat(reporting.status().reportedVersion()).isEqualTo("1.0.0");
            assertThat(reporting.status().lastSuccessAt()).isNotNull();
            assertThat(reporting.status().lastFailureAt()).isNotNull();
            assertThat(reporting.status().lastFailure()).isEqualTo(ManifestFailure.CREDENTIAL_REJECTED);
        } finally {
            iam.stop(0);
        }
    }

    @Test
    void closeStopsPendingRetriesAndDisabledDoesNotAttempt() throws Exception {
        try (var disabled = new ManifestReporting(false, null)) {
            disabled.start(ManifestReporterTest.MANIFEST);
            assertThat(disabled.status().phase()).isEqualTo("DISABLED");
            assertThat(disabled.status().attempts()).isZero();
        }
        var reporting = new ManifestReporting(
                true,
                new ManifestReporter("http://127.0.0.1:1", "secret"),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                2);
        reporting.start(ManifestReporterTest.MANIFEST);
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> reporting.status().attempts() == 1);
        reporting.close();
        assertThat(reporting.status().phase()).isEqualTo("STOPPED");
        Awaitility.await()
                .during(Duration.ofMillis(100))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(reporting.status().attempts()).isEqualTo(1));
    }
}
