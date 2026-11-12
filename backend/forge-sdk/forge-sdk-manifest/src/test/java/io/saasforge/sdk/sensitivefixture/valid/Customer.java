package io.saasforge.sdk.sensitivefixture.valid;

import io.saasforge.sdk.security.SensitiveField;

public record Customer(
        @SensitiveField(path = "customer.phone", grade = "L3", defaultRule = "phone")
        String phone,

        @SensitiveField(path = "customer.name", grade = "L2")
        String name) {
    static {
        if (Boolean.parseBoolean("true")) {
            throw new IllegalStateException("Manifest generation must not initialize business classes");
        }
    }
}
