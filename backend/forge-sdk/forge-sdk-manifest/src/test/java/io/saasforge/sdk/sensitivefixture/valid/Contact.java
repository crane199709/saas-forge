package io.saasforge.sdk.sensitivefixture.valid;

import io.saasforge.sdk.security.SensitiveField;

public class Contact {
    @SensitiveField(path = "contact.email", grade = "L3", defaultRule = "email")
    public String getEmail() {
        return "contact@example.test";
    }
}
