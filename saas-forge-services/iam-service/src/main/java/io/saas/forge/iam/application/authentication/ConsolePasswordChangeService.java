package io.saas.forge.iam.application.authentication;

import static io.saas.forge.iam.application.authentication.ConsoleSessionException.Code.*;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.session.*;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** 首次改密、受限 Family 结束、Slot 推进与非敏感结果记录共享一个事务。 */
public final class ConsolePasswordChangeService {
    private final ConsoleSessionAccess access;
    private final ConsoleSessionRepository slots;
    private final InitialPasswordChangeService passwords;
    private final IdentityRepository identities;
    private final PasswordVerifier verifier;
    private final TransactionTemplate transactions;

    public ConsolePasswordChangeService(ConsoleSessionAccess access, ConsoleSessionRepository slots,
            InitialPasswordChangeService passwords, IdentityRepository identities, PasswordVerifier verifier,
            TransactionTemplate transactions) {
        this.access = access; this.slots = slots; this.passwords = passwords;
        this.identities = identities; this.verifier = verifier; this.transactions = transactions;
    }

    public ConsoleSessionTermination.EndResult change(String locator, String refresh, String revision,
            UUID key, String password, String traceId) {
        ConsoleSessionAccess.requireKey(key);
        return transactions.execute(status -> {
            ConsoleSlot slot = access.lock(locator);
            var previous = slots.operation(slot.id(), key);
            if (previous.isPresent()) {
                var operation = previous.get();
                if (operation.kind() != ConsoleOperation.Kind.PASSWORD_CHANGE)
                    throw new ConsoleSessionException(IDEMPOTENCY_KEY_REUSED);
                // 只记录已建立的 Credential ID，复用其 Argon2id 校验同键请求，避免额外保存密码摘要。
                var credential = identities.findCredential(UUID.fromString(operation.fingerprint())).orElseThrow();
                if (!verifier.matches(password, credential.passwordHash()))
                    throw new ConsoleSessionException(IDEMPOTENCY_KEY_REUSED);
                return new ConsoleSessionTermination.EndResult(operation.resultRevision(), false);
            }
            access.requireRevision(slot, revision);
            access.requireReadable(slot);
            var family = access.family(slot, refresh, false);
            if (family.purpose() != RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE)
                throw new PasswordChangeSessionInvalidException();
            UUID credential = passwords.change(refresh, password, traceId);
            var ended = slot.end();
            slots.save(ended);
            slots.begin(new ConsoleOperation(slot.id(), key, family.id(), ConsoleOperation.Kind.PASSWORD_CHANGE,
                    credential.toString(), null));
            slots.complete(slot.id(), key, ended.revision());
            return new ConsoleSessionTermination.EndResult(ended.revision(), true);
        });
    }
}
