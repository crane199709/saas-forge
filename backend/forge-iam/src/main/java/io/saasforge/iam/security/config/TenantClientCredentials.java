package io.saasforge.iam.security.config;

public record TenantClientCredentials(String secret) {
    @Override
    public String toString() {
        return "TenantClientCredentials[redacted]";
    }
}
