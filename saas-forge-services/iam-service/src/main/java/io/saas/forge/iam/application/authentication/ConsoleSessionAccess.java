package io.saas.forge.iam.application.authentication;

import static io.saas.forge.iam.application.authentication.ConsoleSessionException.Code.*;
import io.saas.forge.iam.domain.session.ConsoleSessionRepository;
import io.saas.forge.iam.domain.session.ConsoleSlot;
import io.saas.forge.iam.domain.session.RefreshTokenFamily;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyRepository;
import java.time.Clock;
import java.util.UUID;

/** 必须在 Slot 事务锁内检查版本和 Family；定位 Cookie 本身不通过会话认证。 */
public final class ConsoleSessionAccess {
    private final ConsoleSessionRepository slots;
    private final RefreshTokenFamilyRepository families;
    private final RefreshTokenIssuer tokens;
    private final Clock clock;

    public ConsoleSessionAccess(ConsoleSessionRepository slots, RefreshTokenFamilyRepository families,
                                RefreshTokenIssuer tokens, Clock clock) {
        this.slots = slots; this.families = families; this.tokens = tokens; this.clock = clock;
    }

    public ConsoleSlot lock(String locator) {
        try {
            return slots.lock(tokens.digest(locator)).orElseThrow(() -> new ConsoleSessionException(SESSION_INVALID));
        } catch (ContextSelectionSessionInvalidException invalid) {
            throw new ConsoleSessionException(SESSION_INVALID);
        }
    }

    public void requireRevision(ConsoleSlot slot, String etag) {
        if (etag == null) throw new ConsoleSessionException(SESSION_REVISION_REQUIRED);
        if (!("\"" + slot.revision() + "\"").equals(etag))
            throw new ConsoleSessionException(SESSION_REVISION_CHANGED);
    }

    public RefreshTokenFamily family(ConsoleSlot slot, String refresh, boolean allowConsumed) {
        if (slot.familyId() == null) throw new ConsoleSessionException(SESSION_INVALID);
        RefreshTokenFamily presented;
        try {
            presented = families.findByTokenDigest(tokens.digest(refresh)).orElse(null);
        } catch (ContextSelectionSessionInvalidException invalid) { presented = null; }
        if (presented == null || !slot.familyId().equals(presented.id()))
            throw new ConsoleSessionException(SESSION_COOKIE_MISMATCH);
        RefreshTokenFamily current = families.lockById(slot.familyId())
                .orElseThrow(() -> new ConsoleSessionException(SESSION_INVALID));
        if (!current.isUsableAt(clock.instant())) throw new ConsoleSessionException(SESSION_INVALID);
        if (!allowConsumed && families.findUsableByTokenDigest(tokens.digest(refresh), clock.instant()).isEmpty())
            throw new ConsoleSessionException(SESSION_INVALID);
        return current;
    }

    public void requireReadable(ConsoleSlot slot) {
        if (slot.transition() == ConsoleSlot.Transition.CONTEXT_REFRESH_REQUIRED)
            throw new ConsoleSessionException(CONTEXT_REFRESH_REQUIRED);
        requireRefreshable(slot);
    }

    public void requireRefreshable(ConsoleSlot slot) {
        if (slot.transition() == ConsoleSlot.Transition.ENDING || slot.transition() == ConsoleSlot.Transition.SWITCH_PENDING)
            throw new ConsoleSessionException(SESSION_TRANSITION_PENDING);
    }

    public static void requireKey(UUID key) {
        if (key == null || key.version() != 7 || key.variant() != 2)
            throw new ConsoleSessionException(VALIDATION_FAILED);
    }
}
