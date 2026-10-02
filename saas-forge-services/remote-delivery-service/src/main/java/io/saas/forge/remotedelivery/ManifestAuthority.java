package io.saas.forge.remotedelivery;

import io.saas.forge.sdk.auth.IdentityContextAccessor;
import io.saas.forge.sdk.auth.PlatformRoleChecker;
import io.saas.forge.sdk.auth.ServiceContextAccessor;
import io.saas.forge.sdk.tenant.TenantContextAccessor;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 仅消费 Starter 已复验的上下文，管理员权限继续由 IAM 当前事实裁决。 */
@Component
final class ManifestAuthority {
    private final IdentityContextAccessor identities;
    private final ServiceContextAccessor services;
    private final TenantContextAccessor tenants;
    private final PlatformRoleChecker roles;
    private final io.saas.forge.sdk.auth.UserAccessTokenSignatureVerifier userTokens;
    private final GrpcTenantContextChecker tenantAuthority;
    ManifestAuthority(IdentityContextAccessor identities, ServiceContextAccessor services,
            TenantContextAccessor tenants, PlatformRoleChecker roles,
            io.saas.forge.sdk.auth.UserAccessTokenSignatureVerifier userTokens, GrpcTenantContextChecker tenantAuthority) {
        this.identities = identities; this.services = services; this.tenants = tenants; this.roles = roles; this.userTokens = userTokens;
        this.tenantAuthority = tenantAuthority;
    }
    UUID ci() {
        var context = services.current().orElseThrow(() -> new ManifestException(401, "SERVICE_ACCESS_TOKEN_REQUIRED"));
        if (!context.scopes().equals(Set.of(ManifestPolicy.REGISTER_SCOPE))) throw new ManifestException(403, "CI_CLIENT_REQUIRED");
        return context.clientId();
    }
    UUID administrator(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new ManifestException(401, "ACCESS_TOKEN_REQUIRED");
        var claims = userTokens.verify(authorization);
        if (claims.membershipId() != null || claims.tenantId() != null) throw new ManifestException(403, "PLATFORM_CONTEXT_REQUIRED");
        var identity = identities.current().orElseThrow(() -> new ManifestException(401, "ACCESS_TOKEN_REQUIRED")).identityId();
        try {
            if (!roles.isAllowed(identity, "PLATFORM_ADMIN")) throw new ManifestException(403, "PLATFORM_ADMIN_REQUIRED");
        } catch (ManifestException refused) { throw refused; }
        catch (RuntimeException unavailable) { throw new ManifestException(503, "PLATFORM_AUTHORITY_UNAVAILABLE"); }
        return identity;
    }
    String tenantScope() {
        var context = tenants.requireCurrent();
        tenantAuthority.requireUsable(context);
        return context.identityId() + "_" + context.tenantId() + "_" + context.membershipId();
    }
}
