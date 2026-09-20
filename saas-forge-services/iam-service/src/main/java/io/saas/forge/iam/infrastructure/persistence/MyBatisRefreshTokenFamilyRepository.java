package io.saas.forge.iam.infrastructure.persistence;

import io.saas.forge.iam.domain.session.RefreshTokenConsumption;
import io.saas.forge.iam.domain.session.RefreshTokenFamily;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyContextChange;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saas.forge.iam.domain.session.RefreshRotation;
import io.saas.forge.iam.domain.shared.Sha256Digest;
import io.saas.forge.iam.infrastructure.persistence.mapper.RefreshTokenMapper;
import io.saas.forge.iam.infrastructure.persistence.record.RefreshTokenFamilyRow;
import io.saas.forge.iam.infrastructure.persistence.record.RefreshTokenRow;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisRefreshTokenFamilyRepository implements RefreshTokenFamilyRepository {

    private final RefreshTokenMapper mapper;

    public MyBatisRefreshTokenFamilyRepository(RefreshTokenMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public RefreshTokenFamily create(RefreshTokenFamily family, Sha256Digest tokenDigest, Instant issuedAt) {
        RefreshTokenFamily persisted = toDomain(mapper.insertFamily(toRow(family)));
        mapper.insertToken(tokenRow(persisted.id(), tokenDigest, issuedAt));
        return persisted;
    }

    @Override
    @Transactional
    public RefreshTokenFamily createConsole(RefreshTokenFamily family, Sha256Digest tokenDigest, Instant issuedAt) {
        var row = toRow(family);
        row.setSessionProtocol("CONSOLE_V2");
        var persisted = toDomain(mapper.insertFamily(row));
        mapper.insertToken(tokenRow(persisted.id(), tokenDigest, issuedAt));
        return persisted;
    }

    @Override
    public Optional<RefreshTokenFamily> findById(UUID familyId) {
        return Optional.ofNullable(mapper.findFamilyById(familyId)).map(MyBatisRefreshTokenFamilyRepository::toDomain);
    }

    @Override
    public Optional<RefreshTokenFamily> lockById(UUID familyId) {
        return Optional.ofNullable(mapper.lockFamilyById(familyId)).map(MyBatisRefreshTokenFamilyRepository::toDomain);
    }

    @Override
    public Optional<RefreshTokenFamily> findUsableSelectionByTokenDigest(Sha256Digest tokenDigest, Instant at) {
        return findUsableByTokenDigest(tokenDigest, at)
                .filter(family -> family.purpose() == RefreshTokenFamilyPurpose.USER_TENANT_SELECTION);
    }

    @Override
    public Optional<RefreshTokenFamily> findUsableByTokenDigest(Sha256Digest tokenDigest, Instant at) {
        RefreshTokenRow token = mapper.findTokenByDigest(tokenDigest.value());
        if (token == null || token.getConsumedAt() != null) {
            return Optional.empty();
        }
        return findById(token.getFamilyId())
                .filter(family -> family.isUsableAt(at));
    }

    @Override
    public Optional<RefreshTokenFamily> findByTokenDigest(Sha256Digest tokenDigest) {
        RefreshTokenRow token = mapper.findTokenByDigest(tokenDigest.value());
        return token == null ? Optional.empty() : findById(token.getFamilyId());
    }

    @Override
    @Transactional
    public RefreshTokenFamilyContextChange switchTenantContext(
            UUID familyId, long expectedContextVersion, UUID membershipId, UUID tenantId) {
        RefreshTokenFamilyRow locked = mapper.lockFamilyById(familyId);
        if (locked == null) {
            return new RefreshTokenFamilyContextChange(
                    RefreshTokenFamilyContextChange.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(locked);
        if (family.contextVersion() != expectedContextVersion) {
            return new RefreshTokenFamilyContextChange(
                    RefreshTokenFamilyContextChange.Status.VERSION_CONFLICT, family);
        }
        RefreshTokenFamily changed = family.switchTenantContext(membershipId, tenantId);
        if (changed == family) {
            return new RefreshTokenFamilyContextChange(
                    RefreshTokenFamilyContextChange.Status.UNCHANGED, family);
        }
        if (mapper.updateFamily(toRow(changed)) != 1) {
            throw new IllegalStateException("Refresh Token Family 上下文保存失败");
        }
        return new RefreshTokenFamilyContextChange(
                RefreshTokenFamilyContextChange.Status.CHANGED, changed);
    }

    @Override
    @Transactional
    public RefreshTokenFamilyContextChange switchWorkContext(
            UUID familyId, RefreshTokenFamilyPurpose purpose, UUID membershipId, UUID tenantId) {
        RefreshTokenFamilyRow locked = mapper.lockFamilyById(familyId);
        if (locked == null) {
            return new RefreshTokenFamilyContextChange(
                    RefreshTokenFamilyContextChange.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(locked);
        RefreshTokenFamily changed = family.selectWorkContext(purpose, membershipId, tenantId);
        if (changed == family) {
            return new RefreshTokenFamilyContextChange(
                    RefreshTokenFamilyContextChange.Status.UNCHANGED, family);
        }
        if (mapper.updateFamily(toRow(changed)) != 1) {
            throw new IllegalStateException("Refresh Token Family 工作视图保存失败");
        }
        return new RefreshTokenFamilyContextChange(
                RefreshTokenFamilyContextChange.Status.CHANGED, changed);
    }

    @Override
    @Transactional
    public RefreshRotation rotateForRefresh(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            Sha256Digest idempotencyKeyDigest,
            long expectedContextVersion,
            UUID membershipId,
            UUID tenantId,
            UUID nextAccessJti,
            Duration recoveryWindow,
            Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshRotation(RefreshRotation.Status.NOT_FOUND, null, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        // 版本校验必须早于消费、恢复或插入，冲突时整个 Prepared Token 结果都可丢弃。
        if (family.contextVersion() != expectedContextVersion) {
            return new RefreshRotation(RefreshRotation.Status.CONTEXT_CHANGED, family, null);
        }
        if (token.getConsumedAt() != null) {
            return recoverOrReplay(token, family, nextDigest, idempotencyKeyDigest, nextAccessJti, at);
        }
        if (family.revokedAt() != null) {
            return new RefreshRotation(RefreshRotation.Status.REVOKED, family, null);
        }
        if (!family.isUsableAt(at)) {
            return new RefreshRotation(RefreshRotation.Status.EXPIRED, family, null);
        }
        if (family.purpose() != RefreshTokenFamilyPurpose.USER_PLATFORM
                && family.purpose() != RefreshTokenFamilyPurpose.USER_TENANT
                && family.purpose() != RefreshTokenFamilyPurpose.USER_TENANT_SELECTION) {
            return new RefreshRotation(RefreshRotation.Status.PURPOSE_MISMATCH, family, null);
        }
        consumeToken(token.getId(), at);
        RefreshTokenFamily used = family.purpose() == RefreshTokenFamilyPurpose.USER_TENANT_SELECTION
                        && membershipId != null
                ? family.selectTenant(membershipId, tenantId, at)
                : family.recordUse(membershipId, tenantId, at);
        mapper.updateFamily(toRow(used));
        RefreshTokenRow successor = mapper.insertToken(tokenRow(used.id(), nextDigest, at));
        if (mapper.recordRotation(token.getId(), idempotencyKeyDigest.value(),
                IamTime.asOffsetDateTime(at.plus(recoveryWindow)), successor.getId(), nextAccessJti) != 1) {
            throw new IllegalStateException("Refresh Token 轮换恢复元数据保存失败");
        }
        return new RefreshRotation(RefreshRotation.Status.ROTATED, used, null);
    }

    @Override
    @Transactional
    public RefreshTokenConsumption consume(Sha256Digest tokenDigest, Instant at) {
        return consumeLocked(tokenDigest, null, null, null, at);
    }

    @Override
    @Transactional
    public RefreshTokenConsumption rotate(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            UUID membershipId,
            UUID tenantId,
            Instant at) {
        return consumeLocked(presentedDigest, nextDigest, membershipId, tenantId, at);
    }

    @Override
    @Transactional
    public RefreshTokenConsumption selectTenantContext(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            UUID membershipId,
            UUID tenantId,
            Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        RefreshTokenConsumption terminal = terminalSelectionState(token, family, at);
        if (terminal != null) {
            return terminal;
        }
        if (mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        RefreshTokenFamily selected = family.selectTenant(membershipId, tenantId, at);
        mapper.updateFamily(toRow(selected));
        mapper.insertToken(tokenRow(selected.id(), nextDigest, at));
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, selected);
    }

    @Override
    @Transactional
    public RefreshTokenConsumption rotateSelection(
            Sha256Digest presentedDigest, Sha256Digest nextDigest, Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        RefreshTokenConsumption terminal = terminalSelectionState(token, family, at);
        if (terminal != null) {
            return terminal;
        }
        if (mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        RefreshTokenFamily used = family.recordUse(null, null, at);
        mapper.updateFamily(toRow(used));
        mapper.insertToken(tokenRow(used.id(), nextDigest, at));
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, used);
    }

    @Override
    @Transactional
    public RefreshTokenConsumption revokeForAuthorizationLoss(Sha256Digest presentedDigest, Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        if (token.getConsumedAt() != null || family.revokedAt() != null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REVOKED, family);
        }
        if (!family.isUsableAt(at)) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.EXPIRED, family);
        }
        if (mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        RefreshTokenFamily revoked = family.revoke(at);
        mapper.updateFamily(toRow(revoked));
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, revoked);
    }

    @Override
    @Transactional
    public RefreshTokenFamily revokeById(UUID familyId, Instant at) {
        RefreshTokenFamilyRow row = mapper.lockFamilyById(familyId);
        if (row == null) {
            throw new IllegalStateException("Refresh Token Family 不存在");
        }
        RefreshTokenFamily family = toDomain(row);
        Instant revokedAt = at.isBefore(family.lastUsedAt()) ? family.lastUsedAt() : at;
        RefreshTokenFamily revoked = family.revoke(revokedAt);
        if (revoked != family && mapper.updateFamily(toRow(revoked)) != 1) {
            throw new IllegalStateException("Refresh Token Family 撤销失败");
        }
        return revoked;
    }

    @Override
    @Transactional
    public RefreshTokenConsumption rejectSelection(Sha256Digest presentedDigest, Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        RefreshTokenConsumption terminal = terminalSelectionState(token, family, at);
        if (terminal != null) {
            return terminal;
        }
        if (mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        RefreshTokenFamily revoked = family.revoke(at);
        mapper.updateFamily(toRow(revoked));
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, revoked);
    }

    @Override
    @Transactional
    public RefreshTokenConsumption consumeInitialPasswordChange(Sha256Digest presentedDigest, Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        if (token.getConsumedAt() != null || family.revokedAt() != null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REVOKED, family);
        }
        if (!family.isUsableAt(at)) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.EXPIRED, family);
        }
        if (family.purpose() != RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.PURPOSE_MISMATCH, family);
        }
        if (mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        RefreshTokenFamily revoked = family.revoke(at);
        mapper.updateFamily(toRow(revoked));
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, revoked);
    }

    @Override
    public int revokeInitialPasswordChangeFamilies(UUID identityId, Instant at) {
        return mapper.revokeInitialPasswordChangeFamilies(identityId, IamTime.asOffsetDateTime(at));
    }

    @Override
    @Transactional
    public RefreshTokenConsumption logout(Sha256Digest presentedDigest, Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        if (family.revokedAt() != null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REVOKED, family);
        }
        if (token.getConsumedAt() == null
                && mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        Instant revokedAt = at.isBefore(family.lastUsedAt()) ? family.lastUsedAt() : at;
        RefreshTokenFamily revoked = family.revoke(revokedAt);
        mapper.updateFamily(toRow(revoked));
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, revoked);
    }

    private RefreshTokenConsumption terminalSelectionState(
            RefreshTokenRow token, RefreshTokenFamily family, Instant at) {
        if (token.getConsumedAt() != null) {
            Instant revokedAt = at.isBefore(family.lastUsedAt()) ? family.lastUsedAt() : at;
            RefreshTokenFamily revoked = family.revoke(revokedAt);
            mapper.updateFamily(toRow(revoked));
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REPLAYED, revoked);
        }
        if (family.revokedAt() != null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REVOKED, family);
        }
        if (!family.isUsableAt(at)) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.EXPIRED, family);
        }
        if (family.purpose() != RefreshTokenFamilyPurpose.USER_TENANT_SELECTION) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.PURPOSE_MISMATCH, family);
        }
        return null;
    }

    private RefreshTokenConsumption consumeLocked(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            UUID membershipId,
            UUID tenantId,
            Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        if (token.getConsumedAt() != null) {
            RefreshTokenFamily revoked = family.revoke(at);
            mapper.updateFamily(toRow(revoked));
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REPLAYED, revoked);
        }
        if (family.revokedAt() != null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REVOKED, family);
        }
        if (!family.isUsableAt(at)) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.EXPIRED, family);
        }
        if (nextDigest != null
                && family.purpose() != RefreshTokenFamilyPurpose.USER_PLATFORM
                && family.purpose() != RefreshTokenFamilyPurpose.USER_TENANT) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.PURPOSE_MISMATCH, family);
        }
        if (mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        RefreshTokenFamily used = family.recordUse(
                nextDigest == null ? family.membershipId() : membershipId,
                nextDigest == null ? family.tenantId() : tenantId,
                at);
        mapper.updateFamily(toRow(used));
        if (nextDigest != null) {
            mapper.insertToken(tokenRow(used.id(), nextDigest, at));
        }
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, used);
    }

    private RefreshRotation recoverOrReplay(
            RefreshTokenRow token,
            RefreshTokenFamily family,
            Sha256Digest nextDigest,
            Sha256Digest idempotencyKeyDigest,
            UUID nextAccessJti,
            Instant at) {
        boolean sameKey = token.getRotationKeyDigest() != null
                && MessageDigest.isEqual(token.getRotationKeyDigest(), idempotencyKeyDigest.value());
        boolean withinWindow = token.getRecoveryExpiresAt() != null
                && !at.isAfter(token.getRecoveryExpiresAt().toInstant());
        if (sameKey && withinWindow && token.getRecoveredAt() == null && family.revokedAt() == null) {
            RefreshTokenRow successor = mapper.lockTokenById(token.getSuccessorTokenId());
            if (successor.getConsumedAt() == null) {
                Instant recoveryAt = at.isBefore(family.lastUsedAt()) ? family.lastUsedAt() : at;
                if (recoveryAt.isBefore(successor.getIssuedAt().toInstant())) {
                    recoveryAt = successor.getIssuedAt().toInstant();
                }
                consumeToken(successor.getId(), recoveryAt);
                RefreshTokenFamily used = family.recordUse(family.membershipId(), family.tenantId(), recoveryAt);
                mapper.updateFamily(toRow(used));
                mapper.insertToken(tokenRow(used.id(), nextDigest, recoveryAt));
                if (mapper.markRecovered(token.getId(), IamTime.asOffsetDateTime(recoveryAt)) != 1) {
                    throw new IllegalStateException("Refresh Token 恢复状态保存失败");
                }
                return new RefreshRotation(
                        RefreshRotation.Status.RECOVERED, used, token.getSuccessorAccessJti());
            }
        }
        Instant revokedAt = at.isBefore(family.lastUsedAt()) ? family.lastUsedAt() : at;
        RefreshTokenFamily revoked = family.revoke(revokedAt);
        mapper.updateFamily(toRow(revoked));
        return new RefreshRotation(RefreshRotation.Status.REPLAYED, revoked, null);
    }

    private void consumeToken(UUID tokenId, Instant at) {
        if (mapper.markTokenConsumed(tokenId, IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
    }

    private static RefreshTokenFamilyRow toRow(RefreshTokenFamily family) {
        RefreshTokenFamilyRow row = new RefreshTokenFamilyRow();
        row.setId(family.id());
        row.setIdentityId(family.identityId());
        row.setFamilyPurpose(family.purpose().name());
        row.setInitialCredentialId(family.initialCredentialId());
        row.setMembershipId(family.membershipId());
        row.setTenantId(family.tenantId());
        row.setContextVersion(family.contextVersion());
        row.setLastUsedAt(IamTime.asOffsetDateTime(family.lastUsedAt()));
        row.setAbsoluteExpiresAt(IamTime.asOffsetDateTime(family.absoluteExpiresAt()));
        row.setRevokedAt(IamTime.asOffsetDateTime(family.revokedAt()));
        return row;
    }

    private static RefreshTokenRow tokenRow(UUID familyId, Sha256Digest digest, Instant issuedAt) {
        RefreshTokenRow row = new RefreshTokenRow();
        row.setFamilyId(familyId);
        row.setTokenDigest(digest.value());
        row.setIssuedAt(IamTime.asOffsetDateTime(issuedAt));
        return row;
    }

    private static RefreshTokenFamily toDomain(RefreshTokenFamilyRow row) {
        return RefreshTokenFamily.restore(row.getId(), row.getIdentityId(),
                RefreshTokenFamilyPurpose.valueOf(row.getFamilyPurpose()), row.getInitialCredentialId(),
                row.getMembershipId(), row.getTenantId(),
                row.getContextVersion(),
                IamTime.asInstant(row.getLastUsedAt()), IamTime.asInstant(row.getAbsoluteExpiresAt()),
                IamTime.asInstant(row.getRevokedAt()));
    }
}
