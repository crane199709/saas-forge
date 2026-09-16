package io.saas.forge.iam.domain.session;

import io.saas.forge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Refresh Token Family 的持久化边界；所有输入凭据均已是摘要。 */
public interface RefreshTokenFamilyRepository {

    RefreshTokenFamily create(RefreshTokenFamily family, Sha256Digest tokenDigest, Instant issuedAt);

    default RefreshTokenFamily createConsole(RefreshTokenFamily family, Sha256Digest tokenDigest, Instant issuedAt) {
        throw new UnsupportedOperationException("Console Family persistence is not configured");
    }

    Optional<RefreshTokenFamily> findById(UUID familyId);

    /** 调用方事务内锁定 Family，供跨 Repository 的安全变更编排。 */
    Optional<RefreshTokenFamily> lockById(UUID familyId);

    Optional<RefreshTokenFamily> findUsableSelectionByTokenDigest(Sha256Digest tokenDigest, Instant at);

    Optional<RefreshTokenFamily> findUsableByTokenDigest(Sha256Digest tokenDigest, Instant at);

    Optional<RefreshTokenFamily> findByTokenDigest(Sha256Digest tokenDigest);

    /**
     * 与 Refresh 提交锁定同一 Family 行；调用方事务提交前持续持有该互斥边界。
     */
    RefreshTokenFamilyContextChange switchTenantContext(
            UUID familyId,
            long expectedContextVersion,
            UUID membershipId,
            UUID tenantId);

    RefreshRotation rotateForRefresh(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            Sha256Digest idempotencyKeyDigest,
            long expectedContextVersion,
            UUID membershipId,
            UUID tenantId,
            UUID nextAccessJti,
            Duration recoveryWindow,
            Instant at);

    RefreshTokenConsumption consume(Sha256Digest tokenDigest, Instant at);

    RefreshTokenConsumption rotate(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            UUID membershipId,
            UUID tenantId,
            Instant at);

    RefreshTokenConsumption selectTenantContext(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            UUID membershipId,
            UUID tenantId,
            Instant at);

    RefreshTokenConsumption rotateSelection(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            Instant at);

    RefreshTokenConsumption revokeForAuthorizationLoss(Sha256Digest presentedDigest, Instant at);

    RefreshTokenFamily revokeById(UUID familyId, Instant at);

    RefreshTokenConsumption rejectSelection(Sha256Digest presentedDigest, Instant at);

    RefreshTokenConsumption consumeInitialPasswordChange(Sha256Digest presentedDigest, Instant at);

    /** 撤销 Identity 的全部 INITIAL_PASSWORD_CHANGE Family，并锁住并发初始改密流程。 */
    int revokeInitialPasswordChangeFamilies(UUID identityId, Instant at);

    RefreshTokenConsumption logout(Sha256Digest presentedDigest, Instant at);
}
