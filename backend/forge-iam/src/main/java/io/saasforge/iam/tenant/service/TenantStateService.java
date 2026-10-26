package io.saasforge.iam.tenant.service;

import io.saasforge.iam.tenant.mapper.TenantMapper;
import io.saasforge.sdk.core.MembershipStateKeys;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 维护租户在 Redis 中的状态（ADR-0005），网关据此拒绝非 active 租户的请求。
 *
 * <p>状态只能经由本服务修改：签发 Token 时只补写缺失的键，不会纠正绕过本服务直接改库造成的不一致。
 */
@Service
public class TenantStateService {
    private final TenantMapper tenants;
    private final StringRedisTemplate redis;

    TenantStateService(TenantMapper tenants, StringRedisTemplate redis) {
        this.tenants = tenants;
        this.redis = redis;
    }

    /** 先提交数据库再覆盖 Redis；Redis 写入失败时需重试，否则已签发的 Token 在过期前仍可访问。 */
    public void changeStatus(UUID tenantId, String status) {
        if (tenants.updateStatus(tenantId, status) == 0) throw new TenantNotFoundException();
        redis.opsForValue().set(MembershipStateKeys.tenantStatus(tenantId), status);
    }

    /**
     * 签发 Token 时补写缺失的状态。只在键不存在时写入：签发前读到的 active 不能覆盖并发停用已发布的状态。
     */
    public void publishActive(UUID tenantId) {
        redis.opsForValue().setIfAbsent(MembershipStateKeys.tenantStatus(tenantId), MembershipStateKeys.ACTIVE);
    }
}
