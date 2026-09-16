package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.session.AccessTokenIssuance;
import io.saas.forge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saas.forge.iam.domain.session.RefreshRotation;
import io.saas.forge.iam.domain.session.RefreshTokenFamily;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saas.forge.iam.domain.session.TenantContextSwitchRepository;
import io.saas.forge.iam.domain.shared.Sha256Digest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class RefreshRotationTransaction {
    private static final Duration REFRESH_IDLE_LIFETIME = Duration.ofMinutes(30);

    private final RefreshTokenFamilyRepository families;
    private final TenantContextSwitchRepository contextSwitches;
    private final AccessTokenIssuanceRepository issuances;
    private final RevocationIndex revocationIndex;
    private final OutboxEventRepository outboxEvents;
    private final RefreshReplayDetectedEventFactory replayEventFactory;
    private final SessionRevokedEventFactory revokedEventFactory;
    private final Duration recoveryWindow;
    private final UserTokenIssuanceFence issuanceFence;

    public RefreshRotationTransaction(
            RefreshTokenFamilyRepository families,
            TenantContextSwitchRepository contextSwitches,
            AccessTokenIssuanceRepository issuances,
            RevocationIndex revocationIndex,
            OutboxEventRepository outboxEvents,
            RefreshReplayDetectedEventFactory replayEventFactory,
            SessionRevokedEventFactory revokedEventFactory,
            Duration recoveryWindow,
            UserTokenIssuanceFence issuanceFence) {
        if (recoveryWindow == null || recoveryWindow.isZero() || recoveryWindow.isNegative()
                || recoveryWindow.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Refresh 恢复窗口必须在 0 到 30 秒之间");
        }
        this.families = families;
        this.contextSwitches = contextSwitches;
        this.issuances = issuances;
        this.revocationIndex = revocationIndex;
        this.outboxEvents = outboxEvents;
        this.replayEventFactory = replayEventFactory;
        this.revokedEventFactory = revokedEventFactory;
        this.recoveryWindow = recoveryWindow;
        this.issuanceFence = issuanceFence;
    }

    /** Token 轮换、恢复撤销、Issuance 和安全事件必须共享数据库事务。 */
    @Transactional
    public Result commit(
            RefreshTokenMaterial presentedToken,
            RefreshTokenMaterial nextToken,
            Sha256Digest idempotencyKeyDigest,
            long expectedContextVersion,
            UUID membershipId,
            UUID tenantId,
            IssuedAccessToken accessToken,
            Instant at,
            String traceId) {
        return commitRotation(presentedToken, nextToken, idempotencyKeyDigest, expectedContextVersion,
                membershipId, tenantId, accessToken, at, traceId, false);
    }

    /** Console 由 Slot 的持久 ENDING 流程在提交后交付重放撤销。 */
    @Transactional
    public Result commitConsole(RefreshTokenMaterial presentedToken, RefreshTokenMaterial nextToken,
            Sha256Digest idempotencyKeyDigest, long expectedContextVersion, UUID membershipId, UUID tenantId,
            IssuedAccessToken accessToken, Instant at, String traceId) {
        return commitRotation(presentedToken, nextToken, idempotencyKeyDigest, expectedContextVersion,
                membershipId, tenantId, accessToken, at, traceId, true);
    }

    private Result commitRotation(RefreshTokenMaterial presentedToken, RefreshTokenMaterial nextToken,
            Sha256Digest idempotencyKeyDigest, long expectedContextVersion, UUID membershipId, UUID tenantId,
            IssuedAccessToken accessToken, Instant at, String traceId, boolean console) {
        if (accessToken != null) {
            issuanceFence.assertIssuable(membershipId, tenantId);
        }
        RefreshRotation rotation = families.rotateForRefresh(
                presentedToken.digest(), nextToken.digest(), idempotencyKeyDigest,
                expectedContextVersion,
                membershipId, tenantId, accessToken == null ? null : accessToken.jti(), recoveryWindow, at);
        if (rotation.status() == RefreshRotation.Status.RECOVERED && rotation.replacedAccessJti() != null) {
            AccessTokenIssuance previous = issuances.findByJti(rotation.replacedAccessJti())
                    .orElseThrow(() -> new IllegalStateException("恢复目标 Access Token Issuance 不存在"));
            revocationIndex.revokeJti(previous.jti(), previous.expiresAt(), at);
            issuances.revoke(previous.jti(), at, "REFRESH_ROTATION_RECOVERED");
        }
        if (rotation.status() == RefreshRotation.Status.ROTATED
                || rotation.status() == RefreshRotation.Status.RECOVERED) {
            if (accessToken != null) {
                RefreshTokenFamily family = rotation.family();
                issuances.create(new AccessTokenIssuance(
                        accessToken.jti(), family.id(), family.identityId(), membershipId, tenantId,
                        accessToken.kid(), accessToken.issuedAt(), accessToken.expiresAt()));
            }
            contextSwitches.findAwaitingRefresh(rotation.family().id()).ifPresent(workflow ->
                    contextSwitches.completePostSwitchRefresh(
                            rotation.family().id(), rotation.family().contextVersion(), true, at));
            return new Result(rotation.status(), OptionalLong.of(cookieMaxAge(at, rotation.family())));
        }
        if (rotation.status() == RefreshRotation.Status.REPLAYED) {
            revokeReplayedFamily(rotation.family(), at, traceId, console);
        }
        return new Result(rotation.status(), OptionalLong.empty());
    }

    private void revokeReplayedFamily(RefreshTokenFamily family, Instant at, String traceId, boolean console) {
        List<AccessTokenIssuance> active = issuances.findUnexpiredByFamilyId(family.id(), at);
        if (!console) for (AccessTokenIssuance issuance : active) {
            revocationIndex.revokeJti(issuance.jti(), issuance.expiresAt(), at);
        }
        for (AccessTokenIssuance issuance : active) {
            issuances.revoke(issuance.jti(), at, "REFRESH_TOKEN_REPLAY");
        }
        outboxEvents.append(replayEventFactory.create(family, active.size(), at, traceId));
        outboxEvents.append(revokedEventFactory.createForRefreshReplay(family, !active.isEmpty(), at, traceId));
    }

    private long cookieMaxAge(Instant at, RefreshTokenFamily family) {
        long absoluteRemaining = Duration.between(at, family.absoluteExpiresAt()).getSeconds();
        return Math.min(REFRESH_IDLE_LIFETIME.getSeconds(), absoluteRemaining);
    }

    public record Result(RefreshRotation.Status status, OptionalLong cookieMaxAgeSeconds) {
    }
}
