package io.saas.forge.iam.application.authentication;

import static io.saas.forge.iam.application.authentication.ConsoleSessionException.Code.*;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.session.*;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** ENDING 与持久撤销先提交，再交付 Redis；中途失败仍可由原 Slot 重试。 */
public final class ConsoleSessionTermination {
    private final ConsoleSessionAccess access;
    private final ConsoleSessionRepository slots;
    private final RefreshTokenFamilyRepository families;
    private final AccessTokenIssuanceRepository issuances;
    private final RevocationIndex revocations;
    private final OutboxEventRepository outbox;
    private final SessionRevokedEventFactory events;
    private final RefreshTokenIssuer tokens;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ConsoleSessionTermination(ConsoleSessionAccess access, ConsoleSessionRepository slots,
            RefreshTokenFamilyRepository families, AccessTokenIssuanceRepository issuances,
            RevocationIndex revocations, OutboxEventRepository outbox, SessionRevokedEventFactory events,
            RefreshTokenIssuer tokens, TransactionTemplate transactions, Clock clock) {
        this.access = access; this.slots = slots; this.families = families; this.issuances = issuances;
        this.revocations = revocations; this.outbox = outbox; this.events = events;
        this.tokens = tokens; this.transactions = transactions; this.clock = clock;
    }

    public EndResult logout(String locator, String revision, UUID key) {
        ConsoleSessionAccess.requireKey(key);
        ConsoleOperation operation = transactions.execute(status -> {
            ConsoleSlot slot = access.lock(locator);
            var previous = slots.operation(slot.id(), key);
            if (previous.isPresent()) {
                if (previous.get().kind() != ConsoleOperation.Kind.LOGOUT)
                    throw new ConsoleSessionException(IDEMPOTENCY_KEY_REUSED);
                return previous.get();
            }
            access.requireRevision(slot, revision);
            var created = new ConsoleOperation(slot.id(), key, slot.familyId(), ConsoleOperation.Kind.LOGOUT, "LOGOUT", null);
            slots.begin(created);
            if (slot.familyId() == null) {
                slots.complete(slot.id(), key, slot.revision());
                return new ConsoleOperation(slot.id(), key, null, created.kind(), created.fingerprint(), slot.revision());
            }
            if (slot.transition() != ConsoleSlot.Transition.ENDING) slots.save(slot.transitionTo(ConsoleSlot.Transition.ENDING));
            revokeDurably(slot.familyId());
            return created;
        });
        if (operation.completed()) return new EndResult(operation.resultRevision(), false);
        deliver(operation.familyId());
        return transactions.execute(status -> {
            ConsoleSlot slot = access.lock(locator);
            var current = slots.operation(slot.id(), key).orElseThrow();
            if (current.completed()) return new EndResult(current.resultRevision(), false);
            if (!operation.familyId().equals(slot.familyId()) || slot.transition() != ConsoleSlot.Transition.ENDING)
                throw new ConsoleSessionException(SESSION_REVISION_CHANGED);
            var ended = slot.end();
            slots.save(ended);
            slots.completeLogout(slot.id(), operation.familyId(), ended.revision());
            return new EndResult(ended.revision(), true);
        });
    }

    /** bootstrap 对旧 Cookie 只做撤销，失败时必须保留 Cookie 以便再次定位。 */
    public void clearPresentedFamily(String token, boolean legacyOnly) {
        if (token == null) return;
        RefreshTokenFamily family;
        try { family = families.findByTokenDigest(tokens.digest(token)).orElse(null); }
        catch (ContextSelectionSessionInvalidException invalid) { return; }
        if (family == null || legacyOnly && slots.isConsoleFamily(family.id())) return;
        UUID familyId = family.id();
        transactions.executeWithoutResult(status -> revokeDurably(familyId));
        deliver(familyId);
    }

    /** 受控启用后先持久阻断旧签发；每次推进有限批次，失败后保留进度。 */
    public void retireLegacyProtocol() {
        transactions.executeWithoutResult(status -> slots.beginLegacyRetirement());
        for (UUID familyId : slots.pendingLegacyFamilies(100)) {
            transactions.executeWithoutResult(status -> revokeDurably(familyId));
            deliver(familyId);
            transactions.executeWithoutResult(status -> slots.confirmLegacyRetirement(familyId));
        }
        requireLegacyRetirement();
    }

    public void requireLegacyRetirement() {
        if (!slots.legacyRetirementComplete()) throw new ConsoleSessionException(SESSION_SECURITY_UNAVAILABLE);
    }

    private void revokeDurably(UUID familyId) {
        var family = families.lockById(familyId).orElseThrow();
        Instant now = clock.instant();
        boolean first = family.revokedAt() == null;
        var revoked = families.revokeById(familyId, now);
        var active = issuances.findUnexpiredByFamilyId(familyId, now);
        for (var issuance : active) issuances.revoke(issuance.jti(), now, "CONSOLE_SESSION_ENDED");
        if (first) outbox.append(events.create(revoked, !active.isEmpty(), now, null));
    }

    private void deliver(UUID familyId) {
        Instant now = clock.instant();
        for (var issuance : issuances.findUnexpiredByFamilyId(familyId, now))
            revocations.revokeJti(issuance.jti(), issuance.expiresAt(), now);
        if (!revocations.isReady()) throw new ConsoleSessionException(SESSION_SECURITY_UNAVAILABLE);
    }

    public record EndResult(long revision, boolean clearRefreshCookie) { }
}
