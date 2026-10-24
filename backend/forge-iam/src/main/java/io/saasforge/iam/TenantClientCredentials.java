package io.saasforge.iam;

record TenantClientCredentials(String secret) {
    @Override
    public String toString() {
        return "TenantClientCredentials[redacted]";
    }
}
