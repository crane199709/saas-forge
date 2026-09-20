package io.saas.forge.iam.domain.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 浏览器 Refresh Token 的稳定会话边界，不保存 Token 明文或摘要。 */
public final class RefreshTokenFamily {

    private static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(8);
    private static final Duration IDLE_LIFETIME = Duration.ofMinutes(30);

    private final UUID id;
    private final UUID identityId;
    private final RefreshTokenFamilyPurpose purpose;
    private final UUID initialCredentialId;
    private final UUID membershipId;
    private final UUID tenantId;
    private final long contextVersion;
    private final Instant lastUsedAt;
    private final Instant absoluteExpiresAt;
    private final Instant revokedAt;

    private RefreshTokenFamily(
            UUID id,
            UUID identityId,
            RefreshTokenFamilyPurpose purpose,
            UUID initialCredentialId,
            UUID membershipId,
            UUID tenantId,
            long contextVersion,
            Instant lastUsedAt,
            Instant absoluteExpiresAt,
            Instant revokedAt) {
        if (identityId == null || purpose == null || lastUsedAt == null || absoluteExpiresAt == null) {
            throw new IllegalArgumentException("Refresh Token Family 的必要字段不能为空");
        }
        if ((membershipId == null) != (tenantId == null)) {
            throw new IllegalArgumentException("Membership 与 Tenant 上下文必须同时存在或同时为空");
        }
        if ((purpose == RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE) != (initialCredentialId != null)) {
            throw new IllegalArgumentException("首次改密会话必须且只能绑定初始凭证");
        }
        if (contextVersion < 0) {
            throw new IllegalArgumentException("Refresh Token Family Context Version 不能为负数");
        }
        if (!absoluteExpiresAt.isAfter(lastUsedAt)) {
            throw new IllegalArgumentException("绝对到期时间必须晚于最后使用时间");
        }
        if (revokedAt != null && revokedAt.isBefore(lastUsedAt)) {
            throw new IllegalArgumentException("撤销时间不能早于最后使用时间");
        }
        this.id = id;
        this.identityId = identityId;
        this.purpose = purpose;
        this.initialCredentialId = initialCredentialId;
        this.membershipId = membershipId;
        this.tenantId = tenantId;
        this.contextVersion = contextVersion;
        this.lastUsedAt = lastUsedAt;
        this.absoluteExpiresAt = absoluteExpiresAt;
        this.revokedAt = revokedAt;
    }

    public static RefreshTokenFamily start(UUID identityId, UUID membershipId, UUID tenantId, Instant loginAt) {
        RefreshTokenFamilyPurpose purpose = membershipId == null
                ? RefreshTokenFamilyPurpose.USER_PLATFORM
                : RefreshTokenFamilyPurpose.USER_TENANT;
        return start(identityId, purpose, membershipId, tenantId, loginAt);
    }

    public static RefreshTokenFamily start(
            UUID identityId,
            RefreshTokenFamilyPurpose purpose,
            UUID membershipId,
            UUID tenantId,
            Instant loginAt) {
        return new RefreshTokenFamily(
                null, identityId, purpose, null, membershipId, tenantId,
                0, loginAt, loginAt.plus(ABSOLUTE_LIFETIME), null);
    }

    public static RefreshTokenFamily startInitialPasswordChange(
            UUID identityId, UUID initialCredentialId, Instant loginAt, Instant credentialExpiresAt) {
        Instant sessionExpiresAt = loginAt.plus(Duration.ofMinutes(10));
        if (credentialExpiresAt.isBefore(sessionExpiresAt)) {
            sessionExpiresAt = credentialExpiresAt;
        }
        return new RefreshTokenFamily(null, identityId, RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE,
                initialCredentialId, null, null, 0, loginAt, sessionExpiresAt, null);
    }

    public static RefreshTokenFamily restore(
            UUID id,
            UUID identityId,
            RefreshTokenFamilyPurpose purpose,
            UUID initialCredentialId,
            UUID membershipId,
            UUID tenantId,
            long contextVersion,
            Instant lastUsedAt,
            Instant absoluteExpiresAt,
            Instant revokedAt) {
        if (id == null) {
            throw new IllegalArgumentException("Refresh Token Family ID 不能为空");
        }
        return new RefreshTokenFamily(
                id, identityId, purpose, initialCredentialId, membershipId, tenantId,
                contextVersion, lastUsedAt, absoluteExpiresAt, revokedAt);
    }

    public RefreshTokenFamily identifiedBy(UUID generatedId) {
        if (generatedId == null || id != null) {
            throw new IllegalStateException("Refresh Token Family ID 状态不合法");
        }
        return new RefreshTokenFamily(
                generatedId, identityId, purpose, initialCredentialId, membershipId, tenantId,
                contextVersion, lastUsedAt, absoluteExpiresAt, revokedAt);
    }

    public RefreshTokenFamily recordUse(UUID nextMembershipId, UUID nextTenantId, Instant usedAt) {
        requireUsableAt(usedAt);
        long nextContextVersion = contextMatches(nextMembershipId, nextTenantId)
                ? contextVersion : contextVersion + 1;
        return new RefreshTokenFamily(
                id, identityId, purpose, initialCredentialId, nextMembershipId, nextTenantId,
                nextContextVersion, usedAt, absoluteExpiresAt, revokedAt);
    }

    public RefreshTokenFamily selectTenant(UUID selectedMembershipId, UUID selectedTenantId, Instant selectedAt) {
        if (purpose != RefreshTokenFamilyPurpose.USER_TENANT_SELECTION) {
            throw new IllegalStateException("只有 Tenant 选择会话可以完成上下文选择");
        }
        requireUsableAt(selectedAt);
        return new RefreshTokenFamily(
                id, identityId, RefreshTokenFamilyPurpose.USER_TENANT, null,
                selectedMembershipId, selectedTenantId, contextVersion + 1,
                selectedAt, absoluteExpiresAt, revokedAt);
    }

    /** Tenant Context 变更只推进 Context Version，不把安全变更伪装成普通活动。 */
    public RefreshTokenFamily switchTenantContext(UUID nextMembershipId, UUID nextTenantId) {
        if (purpose != RefreshTokenFamilyPurpose.USER_TENANT) {
            throw new IllegalStateException("只有 Tenant 会话可以切换上下文");
        }
        if (contextMatches(nextMembershipId, nextTenantId)) {
            return this;
        }
        return new RefreshTokenFamily(
                id, identityId, purpose, initialCredentialId, nextMembershipId, nextTenantId,
                contextVersion + 1, lastUsedAt, absoluteExpiresAt, revokedAt);
    }

    /**
     * 统一 Console 的工作视图切换：平台管理与 Tenant 工作台之间改变 Purpose 与当前上下文，
     * 只推进 Context Version，不更换 Identity，也不延长会话期限。
     */
    public RefreshTokenFamily selectWorkContext(
            RefreshTokenFamilyPurpose nextPurpose, UUID nextMembershipId, UUID nextTenantId) {
        if (purpose == RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE) {
            throw new IllegalStateException("受限首次改密会话不能选择工作视图");
        }
        if (nextPurpose != RefreshTokenFamilyPurpose.USER_PLATFORM
                && nextPurpose != RefreshTokenFamilyPurpose.USER_TENANT) {
            throw new IllegalArgumentException("工作视图只能是平台管理或 Tenant");
        }
        if ((nextPurpose == RefreshTokenFamilyPurpose.USER_PLATFORM) != (nextMembershipId == null)) {
            throw new IllegalArgumentException("平台工作视图必须且只能没有 Membership");
        }
        if (purpose == nextPurpose && contextMatches(nextMembershipId, nextTenantId)) {
            return this;
        }
        return new RefreshTokenFamily(
                id, identityId, nextPurpose, null, nextMembershipId, nextTenantId,
                contextVersion + 1, lastUsedAt, absoluteExpiresAt, revokedAt);
    }

    public RefreshTokenFamily revoke(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("撤销时间不能为空");
        }
        if (revokedAt != null) {
            return this;
        }
        return new RefreshTokenFamily(
                id, identityId, purpose, initialCredentialId, membershipId, tenantId,
                contextVersion, lastUsedAt, absoluteExpiresAt, at);
    }

    public boolean isUsableAt(Instant at) {
        return revokedAt == null && at.isBefore(absoluteExpiresAt) && at.isBefore(lastUsedAt.plus(IDLE_LIFETIME));
    }

    public void requireUsableAt(Instant at) {
        if (at == null || !isUsableAt(at)) {
            throw new IllegalStateException("Refresh Token Family 已失效");
        }
    }

    public UUID id() { return id; }
    public UUID identityId() { return identityId; }
    public RefreshTokenFamilyPurpose purpose() { return purpose; }
    public UUID initialCredentialId() { return initialCredentialId; }
    public UUID membershipId() { return membershipId; }
    public UUID tenantId() { return tenantId; }
    public long contextVersion() { return contextVersion; }
    public Instant lastUsedAt() { return lastUsedAt; }
    public Instant absoluteExpiresAt() { return absoluteExpiresAt; }
    public Instant revokedAt() { return revokedAt; }

    private boolean contextMatches(UUID candidateMembershipId, UUID candidateTenantId) {
        return Objects.equals(membershipId, candidateMembershipId)
                && Objects.equals(tenantId, candidateTenantId);
    }
}
