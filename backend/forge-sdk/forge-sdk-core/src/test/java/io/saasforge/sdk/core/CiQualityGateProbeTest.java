package io.saasforge.sdk.core;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

class CiQualityGateProbeTest {
    @Test
    void rejectsIntentionalFailure() {
        fail("SDK-DELIVERY-01: intentional CI failure probe; remove after verification");
    }
}
