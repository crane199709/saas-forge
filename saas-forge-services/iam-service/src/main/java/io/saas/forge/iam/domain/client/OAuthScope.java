package io.saas.forge.iam.domain.client;

import java.util.Arrays;

public enum OAuthScope {
    REMOTE_MANIFEST_REGISTER("remote-delivery:manifest:register"),
    RUNTIME_READ("runtime:read"),
    RUNTIME_QUOTA_WRITE("runtime:quota:write"),
    TENANT_ACCESS_MEMBERSHIP_READ("tenant-access:membership:read"),
    TENANT_ACCESS_TENANT_READ("tenant-access:tenant:read"),
    IAM_IDENTITY_WRITE("iam:identity:write"),
    IAM_PASSWORD_SETUP_WRITE("iam:password-setup:write"),
    IAM_PLATFORM_ROLE_READ("iam:platform-role:read"),
    IAM_SESSIONS_WRITE("iam:sessions:write"),
    ENTITLEMENT_QUOTA_WRITE("entitlement:quota:write");

    private final String value;

    OAuthScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static OAuthScope fromValue(String value) {
        return Arrays.stream(values())
                .filter(scope -> scope.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不允许的 OAuth Scope: " + value));
    }
}
