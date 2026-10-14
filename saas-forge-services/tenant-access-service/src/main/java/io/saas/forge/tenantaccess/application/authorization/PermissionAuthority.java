package io.saas.forge.tenantaccess.application.authorization;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import java.util.List;

/** 每次调用均从 Tenant Access 持久权威读取，不允许调用方以缓存结果放行。 */
public interface PermissionAuthority {
    boolean allowed(TenantContextSnapshot context, String code);

    boolean isAdministrator(TenantContextSnapshot context);

    List<PermissionEntry> catalog();

    record PermissionEntry(String code, boolean enabled) {}
}
