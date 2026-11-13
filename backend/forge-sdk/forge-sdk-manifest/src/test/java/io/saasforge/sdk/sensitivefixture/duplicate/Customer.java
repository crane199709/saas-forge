package io.saasforge.sdk.sensitivefixture.duplicate;

import io.saasforge.sdk.security.SensitiveField;

public record Customer(
        @SensitiveField(path = "customer.phone", grade = "L3")
        String phone,

        @SensitiveField(path = "customer.phone", grade = "L3")
        String otherPhone) {}
