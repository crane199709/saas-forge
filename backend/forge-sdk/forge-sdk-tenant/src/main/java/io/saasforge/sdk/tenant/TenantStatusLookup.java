package io.saasforge.sdk.tenant;

import java.util.UUID;

// 与 Redis 解耦：forge-sdk-tenant 对 Redis 是可选依赖，拦截器不能直接引用 Redis 类型。
@FunctionalInterface
interface TenantStatusLookup {
    /** 租户当前状态，未发布时为 null。 */
    String status(UUID tenantId);
}
