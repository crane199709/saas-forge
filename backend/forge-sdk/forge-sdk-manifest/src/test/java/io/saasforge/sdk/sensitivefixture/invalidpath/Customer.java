package io.saasforge.sdk.sensitivefixture.invalidpath;

import io.saasforge.sdk.security.SensitiveField;

public record Customer(
        @SensitiveField(path = "customer..phone", grade = "L3")
        String phone) {}
