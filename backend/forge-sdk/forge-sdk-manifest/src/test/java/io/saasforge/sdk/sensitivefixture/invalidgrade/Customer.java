package io.saasforge.sdk.sensitivefixture.invalidgrade;

import io.saasforge.sdk.security.SensitiveField;

public record Customer(
        @SensitiveField(path = "customer.phone", grade = "L5")
        String phone) {}
