package io.saas.forge.iam.domain.client;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** 服务身份 OAuth Client，不携带用户或 Tenant 上下文。 */
public final class OAuthClient {

    private final UUID id;
    private final String displayName;
    private final OAuthClientType clientType;
    private final ReservedServiceKey reservedServiceKey;
    private final Set<OAuthScope> allowedScopes;
    private final OAuthClientStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant revokedAt;

    private OAuthClient(
            UUID id,
            String displayName,
            OAuthClientType clientType,
            ReservedServiceKey reservedServiceKey,
            Set<OAuthScope> allowedScopes,
            OAuthClientStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant revokedAt) {
        if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
            throw new IllegalArgumentException("OAuth Client 显示名必须为 1 到 200 个字符");
        }
        if (allowedScopes == null || allowedScopes.isEmpty()) {
            throw new IllegalArgumentException("OAuth Client 必须声明至少一个 Scope");
        }
        if (clientType == null || status == null || createdAt == null || updatedAt == null
                || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("OAuth Client 的必要字段不能为空");
        }
        validateScopeBoundary(clientType, reservedServiceKey, allowedScopes);
        if ((status == OAuthClientStatus.REVOKED) != (revokedAt != null)) {
            throw new IllegalArgumentException("OAuth Client 吊销状态不一致");
        }
        this.id = id;
        this.displayName = displayName;
        this.clientType = clientType;
        this.reservedServiceKey = reservedServiceKey;
        this.allowedScopes = Collections.unmodifiableSet(new LinkedHashSet<>(allowedScopes));
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.revokedAt = revokedAt;
    }

    public static OAuthClient register(String displayName, Set<OAuthScope> allowedScopes, Instant createdAt) {
        ReservedServiceKey reservedServiceKey = reservedServiceKeyFor(allowedScopes);
        OAuthClientType type = reservedServiceKey == null
                ? OAuthClientType.RUNTIME_SERVICE : OAuthClientType.RESERVED_SERVICE;
        return new OAuthClient(null, displayName, type, reservedServiceKey, allowedScopes,
                OAuthClientStatus.ACTIVE, createdAt, createdAt, null);
    }

    /** 管理入口只创建 Runtime 或精确的 CI Scope，内部身份仍由受控 Bootstrap 管理。 */
    public static OAuthClient registerManaged(String displayName, Set<OAuthScope> scopes, Instant at) {
        OAuthClientType type = Set.of(OAuthScope.REMOTE_MANIFEST_REGISTER).equals(scopes)
                ? OAuthClientType.CI_CLIENT : OAuthClientType.RUNTIME_SERVICE;
        return new OAuthClient(null, displayName, type, null, scopes, OAuthClientStatus.ACTIVE, at, at, null);
    }

    public static OAuthClient registerRuntime(String displayName, Set<OAuthScope> allowedScopes, Instant createdAt) {
        return new OAuthClient(null, displayName, OAuthClientType.RUNTIME_SERVICE, null, allowedScopes,
                OAuthClientStatus.ACTIVE, createdAt, createdAt, null);
    }

    public static OAuthClient restore(
            UUID id,
            String displayName,
            Set<OAuthScope> allowedScopes,
            OAuthClientStatus status,
            Instant createdAt,
            Instant revokedAt) {
        ReservedServiceKey key = reservedServiceKeyFor(allowedScopes);
        return restore(id, displayName,
                key == null ? OAuthClientType.RUNTIME_SERVICE : OAuthClientType.RESERVED_SERVICE,
                key, allowedScopes, status, createdAt, createdAt, revokedAt);
    }

    public static OAuthClient restore(
            UUID id,
            String displayName,
            OAuthClientType clientType,
            ReservedServiceKey reservedServiceKey,
            Set<OAuthScope> allowedScopes,
            OAuthClientStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant revokedAt) {
        if (id == null) {
            throw new IllegalArgumentException("OAuth Client ID 不能为空");
        }
        return new OAuthClient(id, displayName, clientType, reservedServiceKey, allowedScopes,
                status, createdAt, updatedAt, revokedAt);
    }

    public OAuthClient identifiedBy(UUID generatedId) {
        if (generatedId == null || id != null) {
            throw new IllegalStateException("OAuth Client ID 状态不合法");
        }
        return new OAuthClient(generatedId, displayName, clientType, reservedServiceKey, allowedScopes,
                status, createdAt, updatedAt, revokedAt);
    }

    public OAuthClient revoke(Instant at) {
        if (status == OAuthClientStatus.REVOKED) {
            return this;
        }
        if (at == null || at.isBefore(createdAt)) {
            throw new IllegalArgumentException("OAuth Client 吊销时间不合法");
        }
        return new OAuthClient(id, displayName, clientType, reservedServiceKey, allowedScopes,
                OAuthClientStatus.REVOKED, createdAt, at, at);
    }

    public void requireActive() {
        if (status != OAuthClientStatus.ACTIVE) {
            throw new IllegalStateException("OAuth Client 已被吊销");
        }
    }

    public UUID id() { return id; }
    public String displayName() { return displayName; }
    public OAuthClientType clientType() { return clientType; }
    public ReservedServiceKey reservedServiceKey() { return reservedServiceKey; }
    public Set<OAuthScope> allowedScopes() { return allowedScopes; }
    public OAuthClientStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant revokedAt() { return revokedAt; }

    private static void validateScopeBoundary(
            OAuthClientType type, ReservedServiceKey key, Set<OAuthScope> scopes) {
        Set<OAuthScope> expected = switch (key == null ? ReservedServiceKey.IAM : key) {
            case IAM -> Set.of(OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ);
            case TENANT_ACCESS -> Set.of(
                    OAuthScope.IAM_IDENTITY_WRITE, OAuthScope.IAM_PASSWORD_SETUP_WRITE,
                    OAuthScope.IAM_PLATFORM_ROLE_READ, OAuthScope.IAM_SESSIONS_WRITE,
                    OAuthScope.ENTITLEMENT_QUOTA_WRITE);
            case REMOTE_DELIVERY -> Set.of(OAuthScope.IAM_PLATFORM_ROLE_READ,
                    OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ, OAuthScope.TENANT_ACCESS_TENANT_READ);
            case ENTITLEMENT -> Set.of(
                    OAuthScope.TENANT_ACCESS_TENANT_READ, OAuthScope.IAM_PLATFORM_ROLE_READ);
        };
        if (type == OAuthClientType.RUNTIME_SERVICE) {
            if (key != null || !Set.of(OAuthScope.RUNTIME_READ, OAuthScope.RUNTIME_QUOTA_WRITE).containsAll(scopes)) {
                throw new OAuthClientScopeGrantForbiddenException();
            }
        } else if (type == OAuthClientType.CI_CLIENT) {
            if (key != null || !Set.of(OAuthScope.REMOTE_MANIFEST_REGISTER).equals(scopes)) {
                throw new OAuthClientScopeGrantForbiddenException();
            }
        } else if (key == null || !expected.equals(scopes)) {
            throw new IllegalArgumentException("Reserved OAuth Client 的服务键与 Scope 不匹配");
        }
    }

    private static ReservedServiceKey reservedServiceKeyFor(Set<OAuthScope> scopes) {
        if (Set.of(OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ).equals(scopes)) return ReservedServiceKey.IAM;
        if (Set.of(OAuthScope.IAM_IDENTITY_WRITE, OAuthScope.IAM_PASSWORD_SETUP_WRITE,
                OAuthScope.IAM_PLATFORM_ROLE_READ, OAuthScope.IAM_SESSIONS_WRITE,
                OAuthScope.ENTITLEMENT_QUOTA_WRITE).equals(scopes)) return ReservedServiceKey.TENANT_ACCESS;
        if (Set.of(OAuthScope.TENANT_ACCESS_TENANT_READ, OAuthScope.IAM_PLATFORM_ROLE_READ).equals(scopes)) {
            return ReservedServiceKey.ENTITLEMENT;
        }
        if (Set.of(OAuthScope.IAM_PLATFORM_ROLE_READ, OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ,
                OAuthScope.TENANT_ACCESS_TENANT_READ).equals(scopes)) return ReservedServiceKey.REMOTE_DELIVERY;
        return null;
    }
}
