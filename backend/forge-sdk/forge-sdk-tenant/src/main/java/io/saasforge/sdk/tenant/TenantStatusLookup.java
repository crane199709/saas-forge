package io.saasforge.sdk.tenant;

import java.util.UUID;

/**
 * 查询 IAM 发布的租户生命周期状态，供写入保护与 Tenant Task 执行前复查。
 *
 * <p>接口不暴露 Redis 类型，让仅使用数据库隔离的服务无须引入 Redis。
 */
@FunctionalInterface
public interface TenantStatusLookup {
    /**
     * 返回租户当前状态。
     *
     * @param tenantId 要复查的租户 ID
     * @return 生命周期状态（如 trial、active）；未发布时为 null
     */
    String status(UUID tenantId);
}
