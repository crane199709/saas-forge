package io.saas.forge.tenantaccess.infrastructure.persistence;

import io.saas.forge.tenantaccess.application.tenant.TenantDetailsRepository;
import io.saas.forge.tenantaccess.domain.tenant.Tenant;
import io.saas.forge.tenantaccess.domain.tenant.TenantStatus;
import io.saas.forge.tenantaccess.infrastructure.persistence.mapper.TenantDetailsMapper;
import io.saas.forge.tenantaccess.infrastructure.persistence.record.TenantDetailsResultRow;
import io.saas.forge.tenantaccess.infrastructure.persistence.record.TenantRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisTenantDetailsRepository implements TenantDetailsRepository {
    private final TenantDetailsMapper mapper;

    public MyBatisTenantDetailsRepository(TenantDetailsMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void setTarget(UUID tenantId) {
        if (!tenantId.toString().equals(mapper.setOperationTarget(tenantId))) {
            throw new IllegalStateException("Tenant Operation Target 设置失败");
        }
    }

    @Override
    public boolean claim(UUID tenantId, UUID actorIdentityId, UUID idempotencyKey, String fingerprint) {
        return mapper.claim(new TenantDetailsMapper.Claim(
                new TenantDetailsMapper.Key(tenantId, actorIdentityId, idempotencyKey), fingerprint)) == 1;
    }

    @Override
    public Optional<Update> find(UUID tenantId, UUID actorIdentityId, UUID idempotencyKey) {
        TenantDetailsResultRow row = mapper.find(new TenantDetailsMapper.Key(tenantId, actorIdentityId, idempotencyKey));
        if (row == null) return Optional.empty();
        Tenant result = row.updatedAt() == null ? null : new Tenant(tenantId, row.displayName(),
                TenantStatus.valueOf(row.status()), TenantAccessTime.asInstant(row.expiresAt()),
                TenantAccessTime.asInstant(row.createdAt()), TenantAccessTime.asInstant(row.updatedAt()));
        return Optional.of(new Update(row.requestFingerprint(), result));
    }

    @Override
    public Tenant lockTenant(UUID tenantId) {
        TenantRow row = mapper.lockTenant(tenantId);
        return row == null ? null : toDomain(row);
    }

    @Override
    public boolean lifecycleChangeInProgress(UUID tenantId) {
        return mapper.lifecycleChangeInProgress(tenantId);
    }

    @Override
    public void save(Tenant tenant) {
        if (mapper.save(toRow(tenant)) != 1) throw new IllegalStateException("Tenant 修改失败");
    }

    @Override
    public void complete(UUID tenantId, UUID actorIdentityId, UUID idempotencyKey, Tenant result, Instant completedAt) {
        if (mapper.complete(new TenantDetailsMapper.Snapshot(
                new TenantDetailsMapper.Key(tenantId, actorIdentityId, idempotencyKey), toRow(result),
                TenantAccessTime.asOffsetDateTime(completedAt))) != 1) {
            throw new IllegalStateException("Tenant 修改幂等结果保存失败");
        }
    }

    private static Tenant toDomain(TenantRow row) {
        return new Tenant(row.id(), row.displayName(), TenantStatus.valueOf(row.status()),
                TenantAccessTime.asInstant(row.expiresAt()), TenantAccessTime.asInstant(row.createdAt()),
                TenantAccessTime.asInstant(row.updatedAt()));
    }

    private static TenantRow toRow(Tenant tenant) {
        return new TenantRow(tenant.id(), tenant.displayName(), tenant.status().name(),
                TenantAccessTime.asOffsetDateTime(tenant.expiresAt()),
                TenantAccessTime.asOffsetDateTime(tenant.createdAt()),
                TenantAccessTime.asOffsetDateTime(tenant.updatedAt()));
    }
}
