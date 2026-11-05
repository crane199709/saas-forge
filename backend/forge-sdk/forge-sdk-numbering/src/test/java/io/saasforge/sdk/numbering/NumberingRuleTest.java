package io.saasforge.sdk.numbering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NumberingRuleTest {
    @Test
    void dateSegmentIsOptional() {
        assertThat(new NumberingRule("invoice", "INV", null, 6).dateSegment()).isNull();
    }

    @Test
    void declarationsThatCannotProduceAReadableNumberAreRejected() {
        assertThatThrownBy(() -> new NumberingRule(" ", "SO", DateSegment.DAY, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NumberingRule("x".repeat(65), "SO", DateSegment.DAY, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NumberingRule("order", "", DateSegment.DAY, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NumberingRule("order", "SO", DateSegment.DAY, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NumberingRule("order", "SO", DateSegment.DAY, 19))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
