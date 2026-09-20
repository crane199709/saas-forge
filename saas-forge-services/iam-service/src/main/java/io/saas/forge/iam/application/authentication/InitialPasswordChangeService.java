package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.identity.PasswordCredential;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.session.RefreshTokenConsumption;
import io.saas.forge.iam.domain.session.RefreshTokenFamily;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

public class InitialPasswordChangeService {
    private final IdentityRepository identities;
    private final RefreshTokenFamilyRepository refreshTokenFamilies;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final PasswordPolicy passwordPolicy;
    private final CompromisedPasswordChecker compromisedPasswords;
    private final PasswordVerifier passwordVerifier;
    private final OutboxEventRepository outboxEvents;
    private final PasswordChangedEventFactory eventFactory;
    private final Clock clock;

    public InitialPasswordChangeService(
            IdentityRepository identities,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            RefreshTokenIssuer refreshTokenIssuer,
            PasswordPolicy passwordPolicy,
            CompromisedPasswordChecker compromisedPasswords,
            PasswordVerifier passwordVerifier,
            OutboxEventRepository outboxEvents,
            PasswordChangedEventFactory eventFactory,
            Clock clock) {
        this.identities = identities;
        this.refreshTokenFamilies = refreshTokenFamilies;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.passwordPolicy = passwordPolicy;
        this.compromisedPasswords = compromisedPasswords;
        this.passwordVerifier = passwordVerifier;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
        this.clock = clock;
    }

    /** 凭据替换、受限 Family 撤销和 password.changed Outbox 必须共享同一事务。 */
    @Transactional
    public java.util.UUID change(String refreshToken, String newPassword, String traceId) {
        String normalizedPassword = passwordPolicy.normalizeForChange(newPassword);
        if (compromisedPasswords.isCompromised(normalizedPassword)) {
            throw new PasswordCompromisedException();
        }
        Instant changedAt = clock.instant();
        RefreshTokenConsumption consumed;
        try {
            consumed = refreshTokenFamilies.consumeInitialPasswordChange(
                    refreshTokenIssuer.digest(refreshToken), changedAt);
        } catch (ContextSelectionSessionInvalidException exception) {
            throw new PasswordChangeSessionInvalidException();
        }
        if (consumed.status() != RefreshTokenConsumption.Status.CONSUMED) {
            throw new PasswordChangeSessionInvalidException();
        }
        RefreshTokenFamily family = consumed.family();
        PasswordCredential initial = identities.findCredential(family.initialCredentialId())
                .filter(credential -> credential.identityId().equals(family.identityId()) && credential.isValidAt(changedAt))
                .orElseThrow(PasswordChangeSessionInvalidException::new);
        PasswordCredential regular = identities.replaceInitialPassword(initial, PasswordCredential.regular(
                family.identityId(), passwordVerifier.hash(normalizedPassword), changedAt));
        outboxEvents.append(eventFactory.create(family, regular, changedAt, traceId));
        return regular.id();
    }
}
