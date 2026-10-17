package io.saas.forge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.iam.domain.identity.CredentialType;
import io.saas.forge.iam.domain.identity.Identity;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.identity.NormalizedEmail;
import io.saas.forge.iam.domain.identity.PasswordCredential;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.session.RevocationFence;
import io.saas.forge.iam.domain.session.RevocationFenceRepository;
import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import io.saas.forge.iam.domain.session.UserSessionRevocationRepository;
import io.saas.forge.iam.infrastructure.persistence.PasswordRecoveryRepository;
import io.saas.forge.iam.infrastructure.security.RedisPasswordRecoveryRateLimiter;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class PasswordRecoveryRequestPrivacyTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final UUID IDENTITY_ID = UUID.fromString("019535d9-0001-7000-8000-000000000003");
    private static final String EMAIL = "member@example.test";

    private final PasswordRecoveryRepository challenges = mock(PasswordRecoveryRepository.class);
    private final IdentityRepository identities = mock(IdentityRepository.class);
    private final RevocationFenceRepository fences = mock(RevocationFenceRepository.class);
    private final PasswordRecoveryMailer mailer = mock(PasswordRecoveryMailer.class);
    private final RedisPasswordRecoveryRateLimiter rateLimiter = mock(RedisPasswordRecoveryRateLimiter.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final PasswordRecoveryService service = new PasswordRecoveryService(
            challenges, identities, fences, new PasswordSetupChallengeIssuer(new SecureRandom()), mailer,
            rateLimiter, mock(PasswordPolicy.class), mock(CompromisedPasswordChecker.class),
            mock(PasswordVerifier.class), mock(UserSessionRevocationTransaction.class),
            mock(UserSessionRevocationService.class), mock(UserSessionRevocationRepository.class),
            mock(OutboxEventRepository.class), mock(PasswordRecoveredEventFactory.class), transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void unknownOrRateLimitedAddressGetsTheSamePublicOutcomeWithoutMail() {
        NormalizedEmail normalized = NormalizedEmail.from(EMAIL);
        when(rateLimiter.allow(normalized)).thenReturn(false, true);
        assertDoesNotThrow(() -> service.request(EMAIL));
        verify(transactions, never()).execute(any());

        executeTransactions();
        assertDoesNotThrow(() -> service.request("  MEMBER@EXAMPLE.TEST  "));
        verify(identities).findByEmail(normalized);
        verify(mailer, never()).send(any(), any());
        verify(challenges, never()).replace(any(), any(), any());
    }

    @Test
    void activeFenceOrMissingUsablePasswordDoesNotIssueRecoveryLink() {
        executeTransactions();
        NormalizedEmail normalized = NormalizedEmail.from(EMAIL);
        Identity identity = Identity.restore(IDENTITY_ID, normalized, null, NOW.minusSeconds(10));
        when(rateLimiter.allow(normalized)).thenReturn(true);
        when(identities.findByEmail(normalized)).thenReturn(Optional.of(identity));
        when(fences.findActiveIdentity(IDENTITY_ID)).thenReturn(Optional.of(
                RevocationFence.establish(UUID.fromString("019535d9-0001-7000-8000-000000000004"),
                        RevocationFenceTarget.identity(IDENTITY_ID), NOW)));
        assertDoesNotThrow(() -> service.request(EMAIL));
        when(fences.findActiveIdentity(IDENTITY_ID)).thenReturn(Optional.empty());
        PasswordCredential expired = mock(PasswordCredential.class);
        when(expired.type()).thenReturn(CredentialType.PASSWORD);
        when(expired.isValidAt(NOW)).thenReturn(false);
        when(identities.lockCredentials(IDENTITY_ID)).thenReturn(List.of(expired));
        assertDoesNotThrow(() -> service.request(EMAIL));
        verify(challenges, never()).replace(any(), any(), any());
        verify(mailer, never()).send(any(), any());
    }

    @Test
    void mailFailureDoesNotRevealExistingAccountAndPersistsOnlyTokenDigest() {
        executeTransactions();
        NormalizedEmail normalized = NormalizedEmail.from(EMAIL);
        Identity identity = Identity.restore(IDENTITY_ID, normalized, null, NOW.minusSeconds(10));
        when(rateLimiter.allow(normalized)).thenReturn(true);
        when(identities.findByEmail(normalized)).thenReturn(Optional.of(identity));
        PasswordCredential credential = mock(PasswordCredential.class);
        when(credential.type()).thenReturn(CredentialType.PASSWORD);
        when(credential.isValidAt(NOW)).thenReturn(true);
        when(identities.lockCredentials(IDENTITY_ID)).thenReturn(List.of(credential));
        doThrow(new IllegalStateException("mail unavailable")).when(mailer).send(eq(EMAIL), any());
        assertDoesNotThrow(() -> service.request(EMAIL));
        verify(challenges).replace(eq(IDENTITY_ID), any(), eq(NOW));
        verify(mailer).send(eq(EMAIL), any());
    }

    @SuppressWarnings("unchecked")
    private void executeTransactions() {
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<String>) invocation.getArgument(0))
                        .doInTransaction(mock(TransactionStatus.class)));
    }
}
