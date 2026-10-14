package io.saas.forge.tenantaccess.application.invitation;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.administrator.IdentityProvisioningGateway;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.invitation.Invitation;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

public class InvitationService {
    private final InvitationRepository invitations;
    private final IdentityProvisioningGateway identities;
    private final OutboxEventRepository outbox;
    private final InvitationEventFactory events;
    private final InvitationMailer mailer;
    private final TransactionTemplate transactions;
    private final UuidV7Generator ids;
    private final SecureRandom random;
    private final Clock clock;

    public InvitationService(InvitationRepository invitations, IdentityProvisioningGateway identities,
            OutboxEventRepository outbox, InvitationEventFactory events, InvitationMailer mailer,
            TransactionTemplate transactions, UuidV7Generator ids, SecureRandom random, Clock clock) {
        this.invitations = invitations;
        this.identities = identities;
        this.outbox = outbox;
        this.events = events;
        this.mailer = mailer;
        this.transactions = transactions;
        this.ids = ids;
        this.random = random;
        this.clock = clock;
    }

    public List<InvitationRepository.InvitationSummary> list(TenantContextSnapshot context) {
        return transactions.execute(status -> {
            invitations.setTarget(context.tenantId());
            return invitations.list(context.tenantId(), clock.instant());
        });
    }

    public InvitationRepository.InvitationSummary create(TenantContextSnapshot context, String email, String traceId) {
        String normalized = normalizeEmail(email);
        UUID identityRequestId = ids.next();
        UUID identityId = identities.ensure(identityRequestId, normalized, null).identityId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String token = token();
        Invitation invitation = Invitation.create(ids.next(), context.tenantId(), normalized, digest(token), now);
        transactions.executeWithoutResult(status -> {
            invitations.setTarget(context.tenantId());
            if (!invitations.lockAccessibleTenant(context.tenantId(), now))
                throw new InvitationFailure("TENANT_NOT_ACCESSIBLE");
            if (invitations.membershipExists(context.tenantId(), identityId))
                throw new InvitationFailure("MEMBERSHIP_ALREADY_EXISTS");
            if (invitations.pendingEmailExists(context.tenantId(), normalized, now))
                throw new InvitationFailure("INVITATION_ALREADY_PENDING");
            invitations.insert(invitation, identityId, context.identityId());
            outbox.append(events.create("created", invitation, context.identityId(), now, traceId));
        });
        deliver(invitation, 1, token);
        return summary(invitation, "SENT");
    }

    public InvitationRepository.InvitationSummary resend(
            TenantContextSnapshot context, UUID invitationId, String traceId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String token = token();
        Issued updated = transactions.execute(status -> {
            invitations.setTarget(context.tenantId());
            if (!invitations.lockAccessibleTenant(context.tenantId(), now))
                throw new InvitationFailure("TENANT_NOT_ACCESSIBLE");
            Invitation current = invitations.lock(context.tenantId(), invitationId);
            if (current == null) throw new InvitationFailure("INVITATION_NOT_FOUND");
            Invitation resent = current.resend(digest(token), now);
            invitations.saveResend(resent);
            outbox.append(events.create("resent", resent, context.identityId(), now, traceId));
            return new Issued(resent, invitations.generation(context.tenantId(), invitationId));
        });
        deliver(updated.invitation(), updated.generation(), token);
        return summary(updated.invitation(), "SENT");
    }

    public void revoke(
            TenantContextSnapshot context, UUID invitationId, String traceId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        transactions.executeWithoutResult(status -> {
            invitations.setTarget(context.tenantId());
            Invitation current = invitations.lock(context.tenantId(), invitationId);
            if (current == null) throw new InvitationFailure("INVITATION_NOT_FOUND");
            Invitation changed = current.revoke(now);
            invitations.saveRevocation(changed);
            outbox.append(events.create("revoked", changed, context.identityId(), now, traceId));
        });
    }

    private void deliver(Invitation invitation, int generation, String token) {
        boolean delivered = false;
        try {
            mailer.send(invitation.email(), token);
            delivered = true;
        } finally {
            boolean result = delivered;
            transactions.executeWithoutResult(status -> {
                invitations.setTarget(invitation.tenantId());
                invitations.markDelivery(invitation.tenantId(), invitation.id(), generation, result, clock.instant());
            });
        }
    }

    private static InvitationRepository.InvitationSummary summary(Invitation invitation, String delivery) {
        return new InvitationRepository.InvitationSummary(invitation.id(), invitation.email(),
                invitation.status().name(), delivery, invitation.expiresAt(),
                invitation.createdAt(), invitation.updatedAt());
    }

    private static String normalizeEmail(String email) {
        if (email == null) throw new IllegalArgumentException("Invitation 邮箱不合法");
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 3 || normalized.length() > 320
                || !normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("Invitation 邮箱不合法");
        }
        return normalized;
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record Issued(Invitation invitation, int generation) {}
}
