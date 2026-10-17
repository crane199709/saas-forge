package io.saas.forge.tenantaccess.application.tenant;

import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saas.forge.tenantaccess.domain.tenant.Tenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class TenantDetailsService {
    private final TenantDetailsRepository details;
    private final OutboxEventRepository outbox;
    private final TenantDetailsEventFactory events;
    private final Clock clock;

    public TenantDetailsService(TenantDetailsRepository details, OutboxEventRepository outbox,
            TenantDetailsEventFactory events, Clock clock) {
        this.details = details;
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
    }

    /** 幂等结果、Tenant 修改及成功事实必须在同一事务提交；重放不重验当前期限。 */
    @Transactional
    public Tenant update(UUID actorIdentityId, UUID idempotencyKey, UUID tenantId,
            String displayName, Instant expiresAt, String traceId) {
        if (actorIdentityId == null || actorIdentityId.version() != 7
                || tenantId == null || tenantId.version() != 7) {
            throw new IllegalArgumentException("Tenant 或操作者 ID 不合法");
        }
        if (idempotencyKey == null || idempotencyKey.version() != 7) {
            throw new IdempotencyKeyInvalidException();
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
            throw new IllegalArgumentException("Tenant 管理名称不合法");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant normalizedExpiry = expiresAt == null ? null : expiresAt.truncatedTo(ChronoUnit.MICROS);
        String fingerprint = fingerprint(tenantId, displayName, normalizedExpiry);
        details.setTarget(tenantId);
        if (!details.claim(tenantId, actorIdentityId, idempotencyKey, fingerprint)) {
            TenantDetailsRepository.Update original = details.find(tenantId, actorIdentityId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Tenant 修改幂等记录不可见"));
            if (!original.fingerprint().equals(fingerprint)) throw new IdempotencyKeyReusedException();
            if (original.result() == null) throw new IllegalStateException("Tenant 修改尚未完成");
            return original.result();
        }
        Tenant current = details.lockTenant(tenantId);
        if (current == null) throw new TenantLifecycleException("TENANT_NOT_FOUND", "Tenant 不存在");
        if (details.lifecycleChangeInProgress(tenantId)) {
            throw new TenantLifecycleException("TENANT_LIFECYCLE_CHANGE_IN_PROGRESS", "Tenant 生命周期变更进行中");
        }
        Tenant changed = current.updateDetails(displayName, normalizedExpiry, now);
        if (!Objects.equals(current.displayName(), changed.displayName())
                || !Objects.equals(current.expiresAt(), changed.expiresAt())) {
            details.save(changed);
            outbox.append(events.create(changed, actorIdentityId, now, traceId));
        } else {
            changed = current;
        }
        details.complete(tenantId, actorIdentityId, idempotencyKey, changed, now);
        return changed;
    }

    private static String fingerprint(UUID tenantId, String displayName, Instant expiresAt) {
        String canonical = "PUT\n/api/v1/platform/tenants/" + tenantId + "\n" + displayName + "\n" + expiresAt;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
