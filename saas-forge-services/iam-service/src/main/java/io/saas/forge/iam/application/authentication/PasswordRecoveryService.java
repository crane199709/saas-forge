package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.identity.CredentialType;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.identity.NormalizedEmail;
import io.saas.forge.iam.domain.identity.PasswordCredential;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.session.RevocationFenceRepository;
import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import io.saas.forge.iam.domain.session.UserSessionRevocationRepository;
import io.saas.forge.iam.domain.session.UserSessionRevocationStatus;
import io.saas.forge.iam.infrastructure.persistence.PasswordRecoveryRepository;
import io.saas.forge.iam.infrastructure.security.RedisPasswordRecoveryRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** Password Recovery 的邮件申请、凭据替换、全 Identity 会话撤销及持久继续。 */
public final class PasswordRecoveryService {
    private final PasswordRecoveryRepository challenges;
    private final IdentityRepository identities;
    private final RevocationFenceRepository fences;
    private final PasswordSetupChallengeIssuer tokens;
    private final PasswordRecoveryMailer mailer;
    private final RedisPasswordRecoveryRateLimiter rateLimiter;
    private final PasswordPolicy policy;
    private final CompromisedPasswordChecker compromised;
    private final PasswordVerifier verifier;
    private final UserSessionRevocationTransaction revocationTransaction;
    private final UserSessionRevocationService revocations;
    private final UserSessionRevocationRepository workflows;
    private final OutboxEventRepository outbox;
    private final PasswordRecoveredEventFactory events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PasswordRecoveryService(PasswordRecoveryRepository challenges, IdentityRepository identities,
            RevocationFenceRepository fences, PasswordSetupChallengeIssuer tokens, PasswordRecoveryMailer mailer,
            RedisPasswordRecoveryRateLimiter rateLimiter, PasswordPolicy policy,
            CompromisedPasswordChecker compromised, PasswordVerifier verifier,
            UserSessionRevocationTransaction revocationTransaction, UserSessionRevocationService revocations,
            UserSessionRevocationRepository workflows, OutboxEventRepository outbox,
            PasswordRecoveredEventFactory events, TransactionTemplate transactions, Clock clock) {
        this.challenges = challenges;
        this.identities = identities;
        this.fences = fences;
        this.tokens = tokens;
        this.mailer = mailer;
        this.rateLimiter = rateLimiter;
        this.policy = policy;
        this.compromised = compromised;
        this.verifier = verifier;
        this.revocationTransaction = revocationTransaction;
        this.revocations = revocations;
        this.workflows = workflows;
        this.outbox = outbox;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** 存在与否、限额命中和 SMTP 结果均不改变公开响应。 */
    public void request(String email) {
        NormalizedEmail normalized = NormalizedEmail.from(email);
        if (!rateLimiter.allow(normalized)) return;
        var material = tokens.issue();
        String recipient = transactions.execute(status -> {
            var identity = identities.findByEmail(normalized).orElse(null);
            if (identity == null) return null;
            identities.lockIdentity(identity.id());
            if (fences.findActiveIdentity(identity.id()).isPresent()) return null;
            if (identities.lockCredentials(identity.id()).stream()
                    .noneMatch(credential -> credential.type() == CredentialType.PASSWORD
                            && credential.isValidAt(now()))) return null;
            challenges.replace(identity.id(), material.digest(), now());
            return identity.email().value();
        });
        if (recipient != null) {
            try {
                mailer.send(recipient, material.token());
            } catch (RuntimeException ignored) {
                // 邮件失败不暴露 Identity；重新请求会使未送达链接失效。
            }
        }
    }

    public Status redeem(String token, String newPassword) {
        io.saas.forge.iam.domain.shared.Sha256Digest digest;
        try {
            digest = tokens.digest(token);
        } catch (PasswordSetupTokenInvalidException invalid) {
            throw new PasswordRecoveryTokenInvalidException();
        }
        var located = challenges.find(digest).orElseThrow(PasswordRecoveryTokenInvalidException::new);
        if (located.completedAt() != null || located.invalidatedAt() != null) {
            throw new PasswordRecoveryTokenInvalidException();
        }
        if (located.startedAt() == null) {
            String normalized = policy.normalizeForChange(newPassword);
            if (compromised.isCompromised(normalized)) throw new PasswordCompromisedException();
            var hash = verifier.hash(normalized);
            transactions.executeWithoutResult(status -> {
                identities.lockIdentity(located.identityId());
                var challenge = challenges.lock(digest).orElseThrow(PasswordRecoveryTokenInvalidException::new);
                if (challenge.startedAt() != null) return;
                Instant at = now();
                if (challenge.invalidatedAt() != null || !challenge.expiresAt().isAfter(at)) {
                    throw new PasswordRecoveryTokenInvalidException();
                }
                var credentials = identities.lockCredentials(challenge.identityId());
                if (credentials.stream().noneMatch(value -> value.type() == CredentialType.PASSWORD
                        && value.isValidAt(at))) throw new PasswordRecoveryTokenInvalidException();
                // 先建立全 Identity Fence，再替换密码；任一步失败均不报告成功。
                revocationTransaction.prepare(challenge.id(), RevocationFenceTarget.identity(challenge.identityId()), at);
                credentials.stream().filter(value -> value.isValidAt(at))
                        .forEach(value -> identities.invalidate(value.id(), at));
                PasswordCredential replacement = identities.create(PasswordCredential.regular(
                        challenge.identityId(), hash, at));
                challenges.start(challenge.id(), replacement.id(), at);
            });
        }
        return continueRecovery(located.id());
    }

    public Status continueRecovery(UUID challengeId) {
        var challenge = challenges.lockById(challengeId).orElseThrow(PasswordRecoveryTokenInvalidException::new);
        if (challenge.startedAt() == null || challenge.completedAt() != null) {
            throw new PasswordRecoveryTokenInvalidException();
        }
        var target = RevocationFenceTarget.identity(challenge.identityId());
        var workflow = workflows.find(challengeId).orElseThrow();
        try {
            if (workflow.status() == UserSessionRevocationStatus.RECOVERY_REQUIRED) {
                revocations.recover(challengeId, target);
            }
            var result = revocations.revoke(challengeId, target);
            if (result.status() != UserSessionRevocationResult.Status.COMPLETED) return Status.PENDING;
        } catch (RuntimeException failure) {
            return Status.PENDING;
        }
        try {
            finish(challengeId);
            return Status.COMPLETED;
        } catch (RuntimeException failure) {
            return Status.PENDING;
        }
    }

    /** Worker 可在浏览器离开后补齐成功事实；唯一更新确保 Outbox 只追加一次。 */
    public void finish(UUID challengeId) {
        transactions.executeWithoutResult(status -> {
            var challenge = challenges.lockById(challengeId).orElseThrow();
            if (challenge.completedAt() != null) return;
            var workflow = workflows.find(challengeId).orElseThrow();
            if (workflow.status() != UserSessionRevocationStatus.COMPLETED) return;
            revocations.release(challengeId, challengeId,
                    RevocationFenceTarget.identity(challenge.identityId()));
            Instant at = now();
            if (challenges.complete(challengeId, at)) {
                outbox.append(events.create(challenge.identityId(), challenge.id(), challenge.credentialId(), at));
            }
        });
    }

    public void finishCompleted() {
        for (var challenge : challenges.unfinished()) {
            if (workflows.find(challenge.id()).map(value -> value.status() == UserSessionRevocationStatus.COMPLETED)
                    .orElse(false)) finish(challenge.id());
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    public enum Status { PENDING, COMPLETED }
}
