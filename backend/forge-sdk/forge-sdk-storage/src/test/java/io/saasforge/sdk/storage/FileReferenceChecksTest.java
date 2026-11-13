package io.saasforge.sdk.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.saasforge.sdk.core.CurrentTenant;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileReferenceChecksTest {
    @Test
    void batchesReferencesInTheCurrentTenantAndReturnsOnlyUnreferencedCandidates() {
        UUID tenant = UUID.randomUUID();
        UUID referenced = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        FileReferenceChecker checker = new FileReferenceChecker() {
            public Set<String> purposes() {
                return Set.of("attachment");
            }

            public Set<UUID> referencedObjects(String purpose, List<UUID> candidates) {
                assertEquals(tenant, CurrentTenant.require());
                assertEquals("attachment", purpose);
                assertEquals(List.of(referenced, orphan), candidates);
                return Set.of(referenced);
            }
        };
        try (var checks = new FileReferenceChecks(List.of(checker), properties());
                var ignored = CurrentTenant.bind(tenant)) {
            assertEquals(Set.of(orphan), checks.unreferenced("attachment", List.of(referenced, orphan), "expire"));
            assertEquals(3, checks.batchSize());
        }
    }

    @Test
    void missingOrUncoveredCheckersNeverAuthorizeDeletion() {
        UUID id = UUID.randomUUID();
        for (List<FileReferenceChecker> checkers :
                List.of(List.<FileReferenceChecker>of(), List.of(checker("attachment", candidates -> Set.of())))) {
            try (var checks = new FileReferenceChecks(checkers, properties());
                    var ignored = CurrentTenant.bind(UUID.randomUUID())) {
                assertEquals(Set.of(), checks.unreferenced("io-result", List.of(id), "purge"));
            }
        }
    }

    @Test
    void everyCoveringCheckerMustSucceedWithAValidCandidateSubset() {
        UUID id = UUID.randomUUID();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FileReferenceChecks.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        List<java.util.function.Function<List<UUID>, Set<UUID>>> failures = List.of(
                candidates -> null,
                candidates -> Set.of(UUID.randomUUID()),
                candidates -> new java.util.HashSet<>(java.util.Arrays.asList(id, null)),
                candidates -> {
                    throw new IllegalStateException("private-business-data");
                });
        try {
            for (var failure : failures) {
                try (var checks = new FileReferenceChecks(
                                List.of(checker("attachment", candidates -> Set.of()), checker("attachment", failure)),
                                properties());
                        var ignored = CurrentTenant.bind(UUID.randomUUID())) {
                    assertEquals(Set.of(), checks.unreferenced("attachment", List.of(id), "expire"));
                }
            }
            assertTrue(appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("reason=invalid-result")));
            assertTrue(appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("reason=checker-failed")));
            assertTrue(appender.list.stream()
                    .noneMatch(event -> event.getFormattedMessage().contains("private-business-data")
                            || event.getThrowableProxy() != null));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void referencesAreCombinedAcrossAllCoveringCheckers() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        try (var checks = new FileReferenceChecks(
                        List.of(
                                checker("attachment", candidates -> Set.of(first)),
                                checker("attachment", candidates -> Set.of(second))),
                        properties());
                var ignored = CurrentTenant.bind(UUID.randomUUID())) {
            assertEquals(Set.of(orphan), checks.unreferenced("attachment", List.of(first, second, orphan), "purge"));
        }
    }

    @Test
    void aTimedOutUninterruptibleCheckerDoesNotBlockAnotherPurposeOrLeakTenants() {
        var release = new java.util.concurrent.CountDownLatch(1);
        UUID firstTenant = UUID.randomUUID();
        UUID secondTenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var stuck = checker("stuck", candidates -> {
            assertEquals(firstTenant, CurrentTenant.require());
            awaitIgnoringInterrupts(release);
            return Set.of();
        });
        var healthy = checker("healthy", candidates -> {
            assertEquals(secondTenant, CurrentTenant.require());
            return Set.of();
        });
        try (var checks = new FileReferenceChecks(List.of(stuck, healthy), properties())) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                try (var ignored = CurrentTenant.bind(firstTenant)) {
                    assertEquals(Set.of(), checks.unreferenced("stuck", List.of(id), "expire"));
                }
            });
            try (var ignored = CurrentTenant.bind(secondTenant)) {
                assertEquals(Set.of(id), checks.unreferenced("healthy", List.of(id), "purge"));
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void oversizedBatchesAreNotSentToBusinessQueriesAndInvalidLimitsFailStartup() {
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        try (var checks = new FileReferenceChecks(
                        List.of(checker("attachment", candidates -> {
                            invoked.set(true);
                            return Set.of();
                        })),
                        properties());
                var ignored = CurrentTenant.bind(UUID.randomUUID())) {
            assertEquals(
                    Set.of(),
                    checks.unreferenced(
                            "attachment",
                            List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                            "purge"));
            assertFalse(invoked.get());
        }
        assertThrows(IllegalArgumentException.class, () -> new StorageRetentionProperties(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new StorageRetentionProperties(1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new StorageRetentionProperties(1, Duration.ofNanos(1)));
    }

    @Test
    void hungCheckersHaveBoundedConcurrencyAndSkipWithASafeDiagnostic() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FileReferenceChecks.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.atomic.AtomicInteger();
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        try (var checks = new FileReferenceChecks(
                        List.of(checker("stuck", candidates -> {
                            started.incrementAndGet();
                            awaitIgnoringInterrupts(release);
                            return Set.of();
                        })),
                        properties());
                var ignored = CurrentTenant.bind(tenant)) {
            for (int attempt = 0; attempt < 8; attempt++) {
                assertEquals(Set.of(), checks.unreferenced("stuck", List.of(id), "purge"));
            }
            assertEquals(2, started.get());
            assertTrue(appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage()
                            .contains("tenant=" + tenant + " purpose=stuck phase=purge reason=capacity-exhausted")));
        } finally {
            release.countDown();
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void awaitIgnoringInterrupts(java.util.concurrent.CountDownLatch release) {
        boolean done = false;
        while (!done) {
            try {
                release.await();
                done = true;
            } catch (InterruptedException ignored) {
                // 模拟不响应取消的第三方检查器，测试结束后由 latch 释放。
            }
        }
    }

    private static FileReferenceChecker checker(
            String purpose, java.util.function.Function<List<UUID>, Set<UUID>> result) {
        return new FileReferenceChecker() {
            public Set<String> purposes() {
                return Set.of(purpose);
            }

            public Set<UUID> referencedObjects(String ignored, List<UUID> candidates) {
                return result.apply(candidates);
            }
        };
    }

    private static StorageRetentionProperties properties() {
        return new StorageRetentionProperties(3, Duration.ofMillis(100));
    }
}
