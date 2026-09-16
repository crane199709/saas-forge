package io.saas.forge.iam.application.authentication;

import static io.saas.forge.iam.application.authentication.ConsoleSessionException.Code.*;
import io.saas.forge.iam.domain.identity.CredentialType;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.session.*;
import io.saas.forge.iam.domain.shared.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionTemplate;

/** Slot 行锁覆盖登录与轮换提交；浏览器 Web Lock 只负责改善并发体验。 */
public final class ConsoleSessionService {
    private final ConsoleSessionAccess access;
    private final ConsoleSessionRepository slots;
    private final RefreshTokenFamilyRepository families;
    private final AccessTokenIssuanceRepository issuances;
    private final PasswordAuthenticator passwords;
    private final ConsoleSessionAuthority authority;
    private final ConsoleSessionTermination termination;
    private final RefreshTokenIssuer refreshTokens;
    private final UserAccessTokenIssuer accessTokens;
    private final RefreshRotationLease leases;
    private final RefreshRotationTransaction rotations;
    private final OutboxEventRepository outbox;
    private final SessionStartedEventFactory startedEvents;
    private final TransactionTemplate transactions;
    private final UuidV7Generator keys;
    private final Clock clock;
    private final UserTokenIssuanceFence issuanceFence;

    public ConsoleSessionService(ConsoleSessionAccess access, ConsoleSessionRepository slots,
            RefreshTokenFamilyRepository families, AccessTokenIssuanceRepository issuances,
            PasswordAuthenticator passwords, ConsoleSessionAuthority authority, ConsoleSessionTermination termination,
            RefreshTokenIssuer refreshTokens, UserAccessTokenIssuer accessTokens, RefreshRotationLease leases,
            RefreshRotationTransaction rotations, OutboxEventRepository outbox, SessionStartedEventFactory startedEvents,
            TransactionTemplate transactions, UuidV7Generator keys, Clock clock, UserTokenIssuanceFence issuanceFence) {
        this.access = access; this.slots = slots; this.families = families; this.issuances = issuances;
        this.passwords = passwords; this.authority = authority; this.termination = termination;
        this.refreshTokens = refreshTokens; this.accessTokens = accessTokens; this.leases = leases;
        this.rotations = rotations; this.outbox = outbox; this.startedEvents = startedEvents;
        this.issuanceFence = issuanceFence;
        this.transactions = transactions; this.keys = keys; this.clock = clock;
    }

    public Bootstrap bootstrap(String locator, String refresh, List<String> legacyTokens) {
        termination.retireLegacyProtocol();
        for (String token : legacyTokens) termination.clearPresentedFamily(token, true);
        ConsoleSlot existing = transactions.execute(status -> {
            try { return access.lock(locator); }
            catch (ConsoleSessionException invalid) { return null; }
        });
        if (existing != null) return new Bootstrap(existing, null);
        termination.clearPresentedFamily(refresh, false);
        var material = refreshTokens.issue();
        var slot = transactions.execute(status -> slots.create(material.digest()));
        return new Bootstrap(slot, material.value());
    }

    public Authentication login(String locator, String revision, String email, String password) {
        termination.requireLegacyRetirement();
        return guarded(locator, slot -> {
            access.requireRevision(slot, revision);
            if (slot.transition() == ConsoleSlot.Transition.ENDING)
                throw new ConsoleSessionException(SESSION_TRANSITION_PENDING);
            if (slot.familyId() != null) throw new ConsoleSessionException(SESSION_ALREADY_ACTIVE);
            var authenticated = passwords.authenticate(email, password);
            var now = clock.instant();
            var identity = authenticated.identity();
            var credential = authenticated.credential();
            RefreshTokenFamily pending;
            if (credential.type() == CredentialType.INITIAL_PLATFORM_PASSWORD) {
                pending = RefreshTokenFamily.startInitialPasswordChange(identity.id(), credential.id(), now, credential.expiresAt());
            } else {
                var contexts = authority.contexts(identity.id());
                var onlyCompany = contexts.count() == 1 && !contexts.platform() ? contexts.companies().get(0) : null;
                var purpose = contexts.count() != 1 ? RefreshTokenFamilyPurpose.USER_TENANT_SELECTION
                        : onlyCompany == null ? RefreshTokenFamilyPurpose.USER_PLATFORM : RefreshTokenFamilyPurpose.USER_TENANT;
                // v2 未选择阶段复用 Family 的无业务 Token 存储状态；协议标识阻止旧选择入口消费它。
                pending = RefreshTokenFamily.start(identity.id(), purpose,
                        onlyCompany == null ? null : onlyCompany.membershipId(),
                        onlyCompany == null ? null : onlyCompany.tenantId(), now);
            }
            var refresh = refreshTokens.issue();
            var family = families.createConsole(pending, refresh.digest(), now);
            var attached = slot.attach(family.id());
            slots.save(attached);
            var snapshot = authority.snapshot(attached.revision(), family);
            issuanceFence.assertIssuable(family.membershipId(), family.tenantId());
            var token = issue(snapshot);
            if (token != null) issuances.create(new AccessTokenIssuance(token.jti(), family.id(), family.identityId(),
                    family.membershipId(), family.tenantId(), token.kid(), token.issuedAt(), token.expiresAt()));
            outbox.append(startedEvents.create(family, now, null));
            return new Authentication(snapshot, token, refresh.value(), cookieLifetime(family));
        });
    }

    public ConsoleSessionSnapshot session(String locator, String refresh) {
        return guarded(locator, slot -> {
            access.requireReadable(slot);
            return authority.snapshot(slot.revision(), access.family(slot, refresh, false));
        });
    }

    public Authentication refresh(String locator, String refresh, String revision, UUID key) {
        ConsoleSessionAccess.requireKey(key);
        var authentication = guarded(locator, slot -> {
            access.requireRevision(slot, revision);
            access.requireRefreshable(slot);
            var family = access.family(slot, refresh, true);
            if (family.purpose() == RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE)
                throw new ConsoleSessionException(INITIAL_CREDENTIAL_RESTRICTED);
            var snapshot = authority.snapshot(slot.revision(), family);
            var presented = new RefreshTokenMaterial(refresh, refreshTokens.digest(refresh));
            var keyDigest = digest(key.toString());
            var lease = leases.acquire(presented.digest(), keyDigest);
            if (!lease.acquired()) throw new RefreshRotationInProgressException(lease.retryAfterSeconds());
            var token = issue(snapshot);
            var next = refreshTokens.issue();
            var result = rotations.commitConsole(presented, next, keyDigest, family.contextVersion(),
                    family.membershipId(), family.tenantId(), token, clock.instant(), null);
            if (result.status() == RefreshRotation.Status.CONTEXT_CHANGED)
                throw new ConsoleSessionException(REFRESH_CONTEXT_CHANGED);
            if (result.status() == RefreshRotation.Status.REPLAYED)
                slots.save(slot.transitionTo(ConsoleSlot.Transition.ENDING));
            if (result.status() != RefreshRotation.Status.ROTATED && result.status() != RefreshRotation.Status.RECOVERED)
                return new Authentication(null, null, null, 0);
            slots.save(slot.finishRefresh());
            return new Authentication(snapshot, token, next.value(), result.cookieMaxAgeSeconds().orElseThrow());
        });
        if (authentication.snapshot() == null) termination.clearPresentedFamily(refresh, false);
        return authentication;
    }

    private IssuedAccessToken issue(ConsoleSessionSnapshot snapshot) {
        if (snapshot.state() != ConsoleSessionSnapshot.State.AUTHENTICATED) return null;
        var family = snapshot.family();
        return accessTokens.issueConsoleToken(family.identityId(), family.membershipId(), family.tenantId(),
                family.id(), family.contextVersion());
    }

    private <T> T guarded(String locator, Function<ConsoleSlot, T> action) {
        try {
            return transactions.execute(status -> {
                var slot = access.lock(locator);
                try { return action.apply(slot); }
                catch (ConsoleSessionException failure) {
                    if (failure.code() == CURRENT_CONTEXT_REVOKED) throw new AuthorityLost(slot.revision());
                    throw failure;
                }
            });
        } catch (AuthorityLost lost) {
            termination.logout(locator, "\"" + lost.revision + "\"", keys.next());
            throw new ConsoleSessionException(CURRENT_CONTEXT_REVOKED);
        }
    }

    private long cookieLifetime(RefreshTokenFamily family) {
        return Math.min(1800, Duration.between(clock.instant(), family.absoluteExpiresAt()).getSeconds());
    }

    private static Sha256Digest digest(String value) {
        try { return Sha256Digest.of(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class AuthorityLost extends RuntimeException {
        private final long revision;
        private AuthorityLost(long revision) { this.revision = revision; }
    }

    public record Bootstrap(ConsoleSlot slot, String newLocator) {
        @Override public String toString() { return "ConsoleBootstrap[redacted]"; }
    }
    public record Authentication(ConsoleSessionSnapshot snapshot, IssuedAccessToken token, String refresh, long maxAge) {
        @Override public String toString() { return "ConsoleAuthentication[redacted]"; }
    }
}
