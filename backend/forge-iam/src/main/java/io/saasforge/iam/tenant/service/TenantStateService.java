package io.saasforge.iam.tenant.service;

import io.saasforge.iam.tenant.mapper.TenantMapper;
import io.saasforge.sdk.core.MembershipStateKeys;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 维护租户在 Redis 中的状态（ADR-0005），网关据此拒绝试用与正常以外的租户的请求，业务服务的 SDK 据此拒绝停用、过期等租户的写入。
 *
 * <p>状态只能经由本服务修改（来源是 platform 广播的 {@code TenantStatusChanged}）：签发 Token 时只补写缺失的键，
 * 不会纠正绕过本服务直接改库造成的不一致。
 */
@Service
public class TenantStateService {
    private final TenantMapper tenants;
    private final StringRedisTemplate redis;
    private final TenantIpAllowlistService allowlists;

    TenantStateService(TenantMapper tenants, StringRedisTemplate redis, TenantIpAllowlistService allowlists) {
        this.allowlists = allowlists;
        this.tenants = tenants;
        this.redis = redis;
    }

    /**
     * 应用 platform 广播的状态变更，只应用比已应用版本更新的变更，返回是否应用。
     *
     * <p>在消费事务内先改库再写 Redis：Redis 写入失败则事务回滚、消息重投；提交失败时 Redis 已是新状态，重投会让数据库追上。
     * 并发应用同一租户的变更时，后到的版本在行锁上等待先到的提交，不会以旧状态覆盖 Redis。
     */
    @Transactional
    public boolean apply(UUID tenantId, String name, String status, long version) {
        if (tenants.applyStatus(tenantId, name, status, version) == 0) return false;
        redis.opsForValue().set(MembershipStateKeys.tenantStatus(tenantId), status);
        allowlists.publishIfAbsent(tenantId);
        return true;
    }

    /**
     * 签发 Token 时补写缺失的状态。只在键不存在时写入：签发前读到的状态不能覆盖并发变更已发布的状态。
     *
     * @param status 签发时从数据库读到的租户状态
     */
    public void publish(UUID tenantId, String status) {
        redis.opsForValue().setIfAbsent(MembershipStateKeys.tenantStatus(tenantId), status);
    }
}
