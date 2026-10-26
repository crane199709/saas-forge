package io.saasforge.sdk.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {
    @Test
    void carriesVersionVariantAndCurrentTimestamp() {
        long before = System.currentTimeMillis();
        UUID id = UuidV7.generate();
        long after = System.currentTimeMillis();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id.getMostSignificantBits() >>> 16).isBetween(before, after);
    }

    @Test
    void idsFromLaterMillisecondsSortAfterEarlierOnes() throws InterruptedException {
        UUID earlier = UuidV7.generate();
        Thread.sleep(2);
        UUID later = UuidV7.generate();

        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }
}
