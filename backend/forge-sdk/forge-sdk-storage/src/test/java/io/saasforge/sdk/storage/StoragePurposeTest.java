package io.saasforge.sdk.storage;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StoragePurposeTest {
    @Test
    void declarationNormalizesExtensionsAndKeepsRulesImmutable() {
        var extensions = new HashSet<>(Set.of("PDF", "png"));
        var purpose = new StoragePurpose("order-attachment", extensions, 1024, Duration.ofMinutes(3));
        extensions.clear();
        Assertions.assertEquals(Set.of("pdf", "png"), purpose.extensions());
        Assertions.assertThrows(
                UnsupportedOperationException.class, () -> purpose.extensions().add("exe"));
    }

    @Test
    void retentionIsOptionalAndInvalidRetentionPreventsServiceStartup() {
        Assertions.assertNull(new StoragePurpose("permanent", Set.of("pdf"), 1, Duration.ofMinutes(1)).retentionDays());
        Assertions.assertEquals(
                30, new StoragePurpose("temporary", Set.of("pdf"), 1, Duration.ofMinutes(1), 30).retentionDays());
        for (int days : new int[] {0, -1}) {
            try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                context.registerBean(
                        StoragePurpose.class,
                        () -> new StoragePurpose("temporary", Set.of("pdf"), 1, Duration.ofMinutes(1), days));
                Assertions.assertThrows(
                        org.springframework.beans.factory.BeanCreationException.class, context::refresh);
            }
        }
    }

    @Test
    void invalidDeclarationFailsBeforeItCanBeUsed() {
        for (String code :
                new String[] {"", "Order", "order/attachment", "order--attachment", "a".repeat(65), "unspecified"}) {
            Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new StoragePurpose(code, Set.of("pdf"), 1, Duration.ofMinutes(1)));
        }
        for (long size : new long[] {0, -1, 5368709121L}) {
            Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new StoragePurpose("order-attachment", Set.of("pdf"), size, Duration.ofMinutes(1)));
        }
        for (Set<String> extensions : List.of(Set.<String>of(), Set.of(".pdf"), Set.of("*"), Set.of("tar.gz"))) {
            Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new StoragePurpose("order-attachment", extensions, 1, Duration.ofMinutes(1)));
        }
        for (Duration ttl : new Duration[] {
            Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(7).plusSeconds(1), Duration.ofMillis(1500)
        }) {
            Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new StoragePurpose("order-attachment", Set.of("pdf"), 1, ttl));
        }
    }
}
