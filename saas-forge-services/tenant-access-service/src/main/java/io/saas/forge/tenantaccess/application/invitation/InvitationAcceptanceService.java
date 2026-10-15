package io.saas.forge.tenantaccess.application.invitation;

import io.saas.forge.tenantaccess.application.administrator.QuotaUnavailableException;
import io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saas.forge.tenantaccess.application.invitation.InvitationAcceptanceRepository.Attempt;
import io.saas.forge.tenantaccess.application.invitation.InvitationAcceptanceRepository.Target;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.invitation.Invitation;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** IAM 已验证的会话 Identity 是唯一受信调用方；邀请 token 只证明收到链接。 */
public final class InvitationAcceptanceService {
    private final InvitationAcceptanceRepository repository;
    private final InvitationQuotaGateway quota;
    private final OutboxEventRepository outbox;
    private final InvitationEventFactory events;
    private final TransactionTemplate transactions;
    private final UuidV7Generator ids;
    private final Clock clock;

    public InvitationAcceptanceService(InvitationAcceptanceRepository repository, InvitationQuotaGateway quota,
            OutboxEventRepository outbox, InvitationEventFactory events, TransactionTemplate transactions,
            UuidV7Generator ids, Clock clock) {
        this.repository = repository; this.quota = quota; this.outbox = outbox; this.events = events;
        this.transactions = transactions; this.ids = ids; this.clock = clock;
    }

    public Result accept(String token, UUID identityId, UUID key, String traceId) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")
                || identityId == null || identityId.version() != 7
                || key == null || key.version() != 7) {
            throw new InvitationFailure("INVITATION_INVALID");
        }
        String hash = digest(token);
        Target target = repository.locate(hash);
        if (target == null) throw new InvitationFailure("INVITATION_INVALID");
        Attempt attempt = transactions.execute(status -> prepare(target, hash, identityId, key));
        if ("SUCCEEDED".equals(attempt.state())) return result(attempt, true);
        if ("COMPENSATED".equals(attempt.state())) throw new InvitationFailure("INVITATION_ACCEPTANCE_RETRY_REQUIRED");
        if ("COMPENSATING".equals(attempt.state())) return compensate(attempt);
        if ("PREPARED".equals(attempt.state())) {
            try {
                quota.consume(attempt.tenantId(), attempt.consumeId());
            } catch (QuotaUnavailableException failure) {
                transactions.executeWithoutResult(status -> {
                    repository.setTarget(attempt.tenantId());
                    repository.state(attempt, "COMPENSATED", now());
                });
                throw new InvitationFailure(failure.code());
            } catch (RemoteWorkflowUnavailableException unavailable) {
                throw new InvitationFailure("INVITATION_DEPENDENCY_UNAVAILABLE");
            }
            transactions.executeWithoutResult(status -> {
                repository.setTarget(attempt.tenantId());
                repository.state(attempt, "CONSUMED", now());
            });
        }
        try {
            return transactions.execute(status -> activate(attempt, hash, traceId));
        } catch (InvitationFailure failure) {
            return beginCompensation(attempt);
        } catch (RuntimeException uncertain) {
            // 若提交成功但响应丢失，先查已提交状态；不可判定时绝不释放额度。
            try {
                Attempt persisted = transactions.execute(status -> {
                    repository.setTarget(attempt.tenantId());
                    return repository.lockAttempt(attempt.tenantId(), attempt.invitationId());
                });
                if (persisted != null && "SUCCEEDED".equals(persisted.state())) return result(persisted, true);
                return beginCompensation(attempt);
            } catch (RuntimeException unavailable) {
                throw new InvitationFailure("INVITATION_DEPENDENCY_UNAVAILABLE");
            }
        }
    }

    private Attempt prepare(Target target, String hash, UUID identityId, UUID key) {
        repository.setTarget(target.tenantId());
        if (!repository.lockAccessibleTenant(target.tenantId(), now()))
            throw new InvitationFailure("TENANT_NOT_ACCESSIBLE");
        Invitation invitation = repository.lockInvitation(target.tenantId(), target.invitationId());
        if (invitation == null || !hash.equals(invitation.tokenHash()))
            throw new InvitationFailure("INVITATION_INVALID");
        if (!identityId.equals(repository.invitedIdentity(target.tenantId(), target.invitationId())))
            throw new InvitationFailure("INVITATION_IDENTITY_MISMATCH");
        Attempt previous = repository.lockAttempt(target.tenantId(), target.invitationId());
        if (invitation.status() == Invitation.Status.ACCEPTED) {
            if (previous != null && "SUCCEEDED".equals(previous.state()))
                return previous;
            throw new InvitationFailure("INVITATION_ALREADY_ACCEPTED");
        }
        if (invitation.status() == Invitation.Status.REVOKED)
            throw new InvitationFailure("INVITATION_REVOKED");
        if (!invitation.pendingAt(now())) throw new InvitationFailure("INVITATION_EXPIRED");
        if (previous != null) {
            if (previous.key().equals(key)) return previous;
            // 链接重开后可继续同一未完成尝试，仍复用原 consume/release ID。
            if ("PREPARED".equals(previous.state()) || "CONSUMED".equals(previous.state()))
                return previous;
            if (!"COMPENSATED".equals(previous.state()))
                throw new InvitationFailure("INVITATION_ACCEPTANCE_IN_PROGRESS");
        }
        if (repository.membershipExists(target.tenantId(), identityId))
            throw new InvitationFailure("MEMBERSHIP_ALREADY_EXISTS");
        Attempt created = new Attempt(target.invitationId(), target.tenantId(), identityId, key,
                ids.next(), ids.next(), ids.next(), "PREPARED");
        repository.saveAttempt(created, now());
        return created;
    }

    private Result activate(Attempt attempt, String hash, String traceId) {
        repository.setTarget(attempt.tenantId());
        if (!repository.lockAccessibleTenant(attempt.tenantId(), now()))
            throw new InvitationFailure("TENANT_NOT_ACCESSIBLE");
        Invitation invitation = repository.lockInvitation(attempt.tenantId(), attempt.invitationId());
        Attempt persisted = repository.lockAttempt(attempt.tenantId(), attempt.invitationId());
        if (persisted == null || !persisted.key().equals(attempt.key()))
            throw new InvitationFailure("INVITATION_ACCEPTANCE_IN_PROGRESS");
        if ("SUCCEEDED".equals(persisted.state())) return result(persisted, true);
        if (invitation == null || !hash.equals(invitation.tokenHash())
                || !invitation.pendingAt(now()))
            throw new InvitationFailure("INVITATION_NOT_PENDING");
        if (repository.membershipExists(attempt.tenantId(), attempt.identityId()))
            throw new InvitationFailure("MEMBERSHIP_ALREADY_EXISTS");
        repository.activate(attempt, now());
        outbox.append(events.accepted(invitation, attempt.identityId(), attempt.membershipId(), now(), traceId));
        return result(attempt, false);
    }

    private Result beginCompensation(Attempt attempt) {
        boolean succeeded = transactions.execute(status -> {
            repository.setTarget(attempt.tenantId());
            Attempt persisted = repository.lockAttempt(attempt.tenantId(), attempt.invitationId());
            if (persisted == null) throw new InvitationFailure("INVITATION_DEPENDENCY_UNAVAILABLE");
            if ("SUCCEEDED".equals(persisted.state())) return true;
            repository.state(attempt, "COMPENSATING", now());
            return false;
        });
        if (succeeded) return result(attempt, true);
        return compensate(attempt);
    }

    private Result compensate(Attempt attempt) {
        try {
            quota.release(attempt.tenantId(), attempt.releaseId());
        } catch (RemoteWorkflowUnavailableException unavailable) {
            throw new InvitationFailure("INVITATION_ACTIVATION_COMPENSATING");
        }
        transactions.executeWithoutResult(status -> {
            repository.setTarget(attempt.tenantId());
            repository.state(attempt, "COMPENSATED", now());
        });
        throw new InvitationFailure("INVITATION_ACCEPTANCE_RETRY_REQUIRED");
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private static Result result(Attempt attempt, boolean replayed) {
        return new Result(attempt.membershipId(), attempt.tenantId(), replayed);
    }
    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    public record Result(UUID membershipId, UUID tenantId, boolean replayed) {}
}
