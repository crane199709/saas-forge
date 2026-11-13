package io.saasforge.sdk.fixture;

public class AllowedMasking {
    public String mask(String value) {
        return "*".repeat(value.length());
    }
}
