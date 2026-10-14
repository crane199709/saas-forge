package io.saas.forge.tenantaccess.infrastructure.persistence.mapper;

import io.saas.forge.tenantaccess.infrastructure.persistence.record.TenantDetailsResultRow;
import io.saas.forge.tenantaccess.infrastructure.persistence.record.TenantRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface TenantDetailsMapper {
    String setOperationTarget(@Param("tenantId") UUID tenantId);

    int claim(@Param("claim") Claim claim);

    TenantDetailsResultRow find(@Param("key") Key key);

    TenantRow lockTenant(@Param("tenantId") UUID tenantId);

    boolean lifecycleChangeInProgress(@Param("tenantId") UUID tenantId);

    int save(@Param("tenant") TenantRow tenant);

    int complete(@Param("snapshot") Snapshot snapshot);

    record Key(UUID tenantId, UUID actorIdentityId, UUID idempotencyKey) {}

    record Claim(Key key, String fingerprint) {}

    record Snapshot(Key key, TenantRow tenant, OffsetDateTime completedAt) {}
}
