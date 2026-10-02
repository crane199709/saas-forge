package io.saas.forge.iam.application.bootstrap;

import io.saas.forge.iam.domain.client.OAuthScope;
import io.saas.forge.iam.domain.client.ReservedServiceKey;
import java.util.Set;

/** 部署引导允许创建的固定内部服务身份。 */
public enum ReservedServiceClient {
    IAM(ReservedServiceKey.IAM, "iam-service", Set.of(OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ)),
    TENANT_ACCESS(ReservedServiceKey.TENANT_ACCESS, "tenant-access-service", Set.of(
            OAuthScope.IAM_IDENTITY_WRITE,
            OAuthScope.IAM_PASSWORD_SETUP_WRITE,
            OAuthScope.IAM_PLATFORM_ROLE_READ,
            OAuthScope.IAM_SESSIONS_WRITE,
            OAuthScope.ENTITLEMENT_QUOTA_WRITE)),
    ENTITLEMENT(ReservedServiceKey.ENTITLEMENT, "entitlement-service", Set.of(
            OAuthScope.TENANT_ACCESS_TENANT_READ,
            OAuthScope.IAM_PLATFORM_ROLE_READ)),
    REMOTE_DELIVERY(ReservedServiceKey.REMOTE_DELIVERY, "remote-delivery-service", Set.of(
            OAuthScope.IAM_PLATFORM_ROLE_READ, OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ,
            OAuthScope.TENANT_ACCESS_TENANT_READ));

    private final ReservedServiceKey serviceKey;
    private final String displayName;
    private final Set<OAuthScope> allowedScopes;

    ReservedServiceClient(ReservedServiceKey serviceKey, String displayName, Set<OAuthScope> allowedScopes) {
        this.serviceKey = serviceKey;
        this.displayName = displayName;
        this.allowedScopes = Set.copyOf(allowedScopes);
    }

    public ReservedServiceKey serviceKey() {
        return serviceKey;
    }

    public String displayName() {
        return displayName;
    }

    public Set<OAuthScope> allowedScopes() {
        return allowedScopes;
    }
}
