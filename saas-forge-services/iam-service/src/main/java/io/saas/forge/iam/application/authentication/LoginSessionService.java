package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.session.AccessTokenIssuance;
import io.saas.forge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saas.forge.iam.domain.session.RefreshTokenFamily;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saas.forge.iam.domain.session.RefreshTokenConsumption;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class LoginSessionService {
    private static final Duration REFRESH_IDLE_LIFETIME = Duration.ofMinutes(30);
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";

    private final PlatformRoleAssignmentRepository platformRoles;
    private final RefreshTokenFamilyRepository refreshTokenFamilies;
    private final AccessTokenIssuanceRepository accessTokenIssuances;
    private final OutboxEventRepository outboxEvents;
    private final SessionStartedEventFactory eventFactory;
    private final UserTokenIssuanceFence issuanceFence;

    public LoginSessionService(
            PlatformRoleAssignmentRepository platformRoles,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            AccessTokenIssuanceRepository accessTokenIssuances,
            OutboxEventRepository outboxEvents,
            SessionStartedEventFactory eventFactory,
            UserTokenIssuanceFence issuanceFence) {
        this.platformRoles = platformRoles;
        this.refreshTokenFamilies = refreshTokenFamilies;
        this.accessTokenIssuances = accessTokenIssuances;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
        this.issuanceFence = issuanceFence;
    }

    /** Platform Role 复核以及 Family、Issuance、Outbox 写入必须共享这一事务边界。 */
    @Transactional
    public long startAccessTokenSession(
            UUID identityId,
            RefreshTokenFamilyPurpose purpose,
            UUID membershipId,
            UUID tenantId,
            IssuedAccessToken accessToken,
            RefreshTokenMaterial refreshToken,
            String traceId) {
        if (purpose != RefreshTokenFamilyPurpose.USER_PLATFORM
                && purpose != RefreshTokenFamilyPurpose.USER_TENANT) {
            throw new IllegalArgumentException("Access Token 会话 Purpose 不合法");
        }
        if ((purpose == RefreshTokenFamilyPurpose.USER_PLATFORM) != (membershipId == null)) {
            throw new IllegalArgumentException("会话 Purpose 与 Tenant 上下文不匹配");
        }
        if (purpose == RefreshTokenFamilyPurpose.USER_PLATFORM
                && !platformRoles.hasActiveAssignment(identityId, PLATFORM_ADMIN_ROLE, accessToken.issuedAt())) {
            throw new AccessContextUnavailableException();
        }
        issuanceFence.assertIdentityIssuable(identityId);
        issuanceFence.assertIssuable(membershipId, tenantId);
        RefreshTokenFamily family = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identityId, purpose, membershipId, tenantId, accessToken.issuedAt()),
                refreshToken.digest(), accessToken.issuedAt());
        accessTokenIssuances.create(new AccessTokenIssuance(
                accessToken.jti(), family.id(), identityId, membershipId, tenantId, accessToken.kid(),
                accessToken.issuedAt(), accessToken.expiresAt()));
        outboxEvents.append(eventFactory.create(family, accessToken.issuedAt(), traceId));
        return cookieMaxAge(accessToken.issuedAt(), family);
    }

    @Transactional
    public long startSelectionSession(
            UUID identityId,
            RefreshTokenMaterial refreshToken,
            Instant startedAt,
            String traceId) {
        issuanceFence.assertIdentityIssuable(identityId);
        RefreshTokenFamily family = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identityId, RefreshTokenFamilyPurpose.USER_TENANT_SELECTION,
                        null, null, startedAt),
                refreshToken.digest(), startedAt);
        outboxEvents.append(eventFactory.create(family, startedAt, traceId));
        return cookieMaxAge(startedAt, family);
    }

    @Transactional
    public long startInitialPasswordChangeSession(
            UUID identityId,
            UUID initialCredentialId,
            Instant credentialExpiresAt,
            RefreshTokenMaterial refreshToken,
            Instant startedAt,
            String traceId) {
        issuanceFence.assertIdentityIssuable(identityId);
        RefreshTokenFamily family = refreshTokenFamilies.create(
                RefreshTokenFamily.startInitialPasswordChange(
                        identityId, initialCredentialId, startedAt, credentialExpiresAt),
                refreshToken.digest(), startedAt);
        outboxEvents.append(eventFactory.create(family, startedAt, traceId));
        return cookieMaxAge(startedAt, family);
    }

    /** 旧选择 Token 的消费、Family purpose 转换和 Access Token 签发事实必须原子提交。 */
    @Transactional
    public OptionalLong completeSelection(
            RefreshTokenMaterial presentedToken,
            RefreshTokenMaterial nextToken,
            UUID membershipId,
            UUID tenantId,
            IssuedAccessToken accessToken,
            Instant selectedAt) {
        issuanceFence.assertIssuable(membershipId, tenantId);
        var selection = refreshTokenFamilies.selectTenantContext(
                presentedToken.digest(), nextToken.digest(), membershipId, tenantId, selectedAt);
        if (selection.status() != RefreshTokenConsumption.Status.CONSUMED) {
            return OptionalLong.empty();
        }
        RefreshTokenFamily family = selection.family();
        issuanceFence.assertIdentityIssuable(family.identityId());
        accessTokenIssuances.create(new AccessTokenIssuance(
                accessToken.jti(), family.id(), family.identityId(), membershipId, tenantId, accessToken.kid(),
                accessToken.issuedAt(), accessToken.expiresAt()));
        return OptionalLong.of(cookieMaxAge(selectedAt, family));
    }

    /** Refresh Token 轮换和新的 Access Token Issuance 必须在同一事务内提交。 */
    @Transactional
    public OptionalLong rotateAccessTokenSession(
            RefreshTokenMaterial presentedToken,
            RefreshTokenMaterial nextToken,
            UUID membershipId,
            UUID tenantId,
            IssuedAccessToken accessToken,
            Instant refreshedAt) {
        issuanceFence.assertIssuable(membershipId, tenantId);
        RefreshTokenConsumption rotation = refreshTokenFamilies.rotate(
                presentedToken.digest(), nextToken.digest(), membershipId, tenantId, refreshedAt);
        if (rotation.status() != RefreshTokenConsumption.Status.CONSUMED) {
            return OptionalLong.empty();
        }
        RefreshTokenFamily family = rotation.family();
        issuanceFence.assertIdentityIssuable(family.identityId());
        accessTokenIssuances.create(new AccessTokenIssuance(
                accessToken.jti(), family.id(), family.identityId(), membershipId, tenantId, accessToken.kid(),
                accessToken.issuedAt(), accessToken.expiresAt()));
        return OptionalLong.of(cookieMaxAge(refreshedAt, family));
    }

    @Transactional
    public OptionalLong rotateSelectionSession(
            RefreshTokenMaterial presentedToken,
            RefreshTokenMaterial nextToken,
            Instant refreshedAt) {
        RefreshTokenConsumption rotation = refreshTokenFamilies.rotateSelection(
                presentedToken.digest(), nextToken.digest(), refreshedAt);
        if (rotation.status() != RefreshTokenConsumption.Status.CONSUMED) {
            return OptionalLong.empty();
        }
        issuanceFence.assertIdentityIssuable(rotation.family().identityId());
        return OptionalLong.of(cookieMaxAge(refreshedAt, rotation.family()));
    }

    @Transactional
    public void revokeForAuthorizationLoss(RefreshTokenMaterial presentedToken, Instant rejectedAt) {
        refreshTokenFamilies.revokeForAuthorizationLoss(presentedToken.digest(), rejectedAt);
    }

    @Transactional
    public void rejectSelection(RefreshTokenMaterial presentedToken, Instant rejectedAt) {
        refreshTokenFamilies.rejectSelection(presentedToken.digest(), rejectedAt);
    }

    private long cookieMaxAge(Instant startedAt, RefreshTokenFamily family) {
        long absoluteRemaining = Duration.between(startedAt, family.absoluteExpiresAt()).getSeconds();
        return Math.min(REFRESH_IDLE_LIFETIME.getSeconds(), absoluteRemaining);
    }
}
