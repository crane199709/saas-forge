package io.saas.forge.iam.infrastructure.persistence;

import io.saas.forge.iam.domain.session.AccessTokenIssuance;
import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import io.saas.forge.iam.domain.session.RevocationFenceTargetType;
import io.saas.forge.iam.domain.session.UserSessionRevocationBatch;
import io.saas.forge.iam.domain.session.UserSessionFenceRelease;
import io.saas.forge.iam.domain.session.UserSessionRevocationRepository;
import io.saas.forge.iam.domain.session.UserSessionRevocationStatus;
import io.saas.forge.iam.domain.session.UserSessionRevocationWorkflow;
import io.saas.forge.iam.infrastructure.persistence.mapper.UserSessionRevocationMapper;
import io.saas.forge.iam.infrastructure.persistence.record.UserSessionRevocationCandidateRow;
import io.saas.forge.iam.infrastructure.persistence.record.UserSessionRevocationRow;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisUserSessionRevocationRepository implements UserSessionRevocationRepository {
    private final UserSessionRevocationMapper mapper;
    public MyBatisUserSessionRevocationRepository(UserSessionRevocationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<UserSessionRevocationWorkflow> find(UUID requestId) {
        return Optional.ofNullable(mapper.find(requestId)).map(MyBatisUserSessionRevocationRepository::toDomain);
    }

    @Override
    public UserSessionRevocationWorkflow create(UUID requestId, RevocationFenceTarget target, Instant at) {
        mapper.insert(requestId, IamTime.asOffsetDateTime(at));
        UserSessionRevocationWorkflow created = find(requestId).orElseThrow();
        if (!created.target().equals(target)) {
            throw new IllegalStateException("撤销请求目标绑定不一致");
        }
        return created;
    }

    @Override
    public Optional<UserSessionRevocationWorkflow> claim(
            UUID requestId, String claimant, Instant now, Instant leaseUntil, int maximumAttempts) {
        mapper.exhaustExpiredAtLimit(IamTime.asOffsetDateTime(now), maximumAttempts,
                "RECOVERY_ATTEMPT_LIMIT_REACHED");
        UserSessionRevocationRow claimed = mapper.claim(requestId, claimant, IamTime.asOffsetDateTime(now),
                IamTime.asOffsetDateTime(leaseUntil), maximumAttempts);
        return claimed == null ? Optional.empty() : find(requestId);
    }

    @Override
    public Optional<UserSessionRevocationWorkflow> claimNext(
            String claimant, Instant now, Instant leaseUntil, int maximumAttempts) {
        mapper.exhaustExpiredAtLimit(IamTime.asOffsetDateTime(now), maximumAttempts,
                "RECOVERY_ATTEMPT_LIMIT_REACHED");
        UserSessionRevocationRow claimed = mapper.claimNext(claimant, IamTime.asOffsetDateTime(now),
                IamTime.asOffsetDateTime(leaseUntil), maximumAttempts);
        return claimed == null ? Optional.empty() : find(claimed.getRevocationRequestId());
    }

    @Override
    public UserSessionRevocationBatch loadBatch(
            UserSessionRevocationWorkflow workflow, int batchSize, Instant at) {
        List<UserSessionRevocationCandidateRow> rows = mapper.findCandidates(
                workflow.target().type().name(), workflow.target().tenantId(), workflow.target().membershipId(),
                workflow.target().identityId(),
                workflow.cursorFamilyId(), IamTime.asOffsetDateTime(at), batchSize);
        LinkedHashSet<UUID> scanned = new LinkedHashSet<>();
        LinkedHashSet<UUID> families = new LinkedHashSet<>();
        var issuances = new java.util.ArrayList<AccessTokenIssuance>();
        for (UserSessionRevocationCandidateRow row : rows) {
            scanned.add(row.getFamilyId());
            if (row.isRevokeFamily()) families.add(row.getFamilyId());
            if (row.getJti() != null) {
                issuances.add(new AccessTokenIssuance(row.getJti(), row.getFamilyId(), row.getIdentityId(),
                        row.getMembershipId(), row.getTenantId(), row.getKid(),
                        row.getIssuedAt().toInstant(), row.getExpiresAt().toInstant()));
            }
        }
        UUID cursor = scanned.isEmpty() ? workflow.cursorFamilyId() : scanned.stream().reduce((a, b) -> b).orElseThrow();
        return new UserSessionRevocationBatch(List.copyOf(families), issuances, cursor, scanned.size() < batchSize);
    }

    @Override
    @Transactional
    public UserSessionRevocationWorkflow commitBatch(
            UserSessionRevocationWorkflow workflow, UserSessionRevocationBatch batch, Instant at) {
        int familyCount = batch.familyIds().isEmpty() ? 0
                : mapper.revokeFamilies(batch.familyIds(), IamTime.asOffsetDateTime(at));
        List<UUID> jtis = batch.issuances().stream().map(AccessTokenIssuance::jti).toList();
        int jtiCount = jtis.isEmpty() ? 0 : mapper.revokeIssuances(jtis, IamTime.asOffsetDateTime(at));
        if (mapper.advance(workflow.revocationRequestId(), workflow.fencingToken(), batch.nextCursor(),
                familyCount, jtiCount, batch.lastBatch(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("User Session Revocation 租约已失效");
        }
        UserSessionRevocationWorkflow updated = find(workflow.revocationRequestId()).orElseThrow();
        return updated;
    }

    @Override
    public void scheduleRetry(UserSessionRevocationWorkflow workflow, Instant retryAt, String failureSummary) {
        if (mapper.scheduleRetry(workflow.revocationRequestId(), workflow.fencingToken(),
                IamTime.asOffsetDateTime(retryAt), failureSummary) != 1) throw staleLease();
    }

    @Override
    public void exhaust(UserSessionRevocationWorkflow workflow, Instant at, String failureSummary) {
        if (mapper.exhaust(workflow.revocationRequestId(), workflow.fencingToken(),
                IamTime.asOffsetDateTime(at), failureSummary) != 1) throw staleLease();
    }

    @Override
    public void recover(UUID requestId, Instant at) {
        if (mapper.recover(requestId, IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("User Session Revocation 不处于显式恢复状态");
        }
    }

    @Override
    public Optional<UserSessionFenceRelease> findRelease(UUID releaseRequestId) {
        return Optional.ofNullable(mapper.findRelease(releaseRequestId))
                .map(MyBatisUserSessionRevocationRepository::toDomain)
                .map(workflow -> new UserSessionFenceRelease(
                        releaseRequestId, workflow.revocationRequestId(), workflow.target()));
    }

    @Override
    public void recordRelease(UUID releaseRequestId, UUID revocationRequestId, RevocationFenceTarget target, Instant at) {
        if (mapper.insertRelease(releaseRequestId, revocationRequestId, target.type().name(),
                target.targetId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("User Session Fence Release 保存失败");
        }
    }

    private static UserSessionRevocationWorkflow toDomain(UserSessionRevocationRow row) {
        RevocationFenceTarget target = switch (RevocationFenceTargetType.valueOf(row.getTargetType())) {
            case TENANT -> RevocationFenceTarget.tenant(row.getTenantId());
            case MEMBERSHIP -> RevocationFenceTarget.membership(row.getMembershipId(), row.getTenantId());
            case IDENTITY -> RevocationFenceTarget.identity(row.getIdentityId());
        };
        return new UserSessionRevocationWorkflow(row.getRevocationRequestId(), target,
                UserSessionRevocationStatus.valueOf(row.getRevocationStatus()), row.getCursorFamilyId(),
                row.getRevokedFamilyCount(), row.getRevokedJtiCount(), row.getAttemptCount(),
                IamTime.asInstant(row.getNextAttemptAt()), row.getLeaseOwner(), IamTime.asInstant(row.getLeaseUntil()),
                row.getFencingToken(), IamTime.asInstant(row.getRecoveryExhaustedAt()), row.getLastFailure(),
                IamTime.asInstant(row.getCompletedAt()));
    }

    private static IllegalStateException staleLease() {
        return new IllegalStateException("User Session Revocation 租约已失效");
    }
}
