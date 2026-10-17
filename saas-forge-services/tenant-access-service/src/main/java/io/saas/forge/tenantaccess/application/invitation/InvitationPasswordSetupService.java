package io.saas.forge.tenantaccess.application.invitation;

import io.saas.forge.tenantaccess.domain.invitation.Invitation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** 收到当前 Invitation 链接才能请求 IAM 投递；此步骤不建立 Membership 或占用 Quota。 */
public final class InvitationPasswordSetupService {
    private final InvitationAcceptanceRepository invitations;
    private final InvitationPasswordSetupGateway setups;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public InvitationPasswordSetupService(InvitationAcceptanceRepository invitations,
            InvitationPasswordSetupGateway setups, TransactionTemplate transactions, Clock clock) {
        this.invitations = invitations;
        this.setups = setups;
        this.transactions = transactions;
        this.clock = clock;
    }

    public InvitationPasswordSetupGateway.Result request(String token, UUID requestId) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")
                || requestId == null || requestId.version() != 7) {
            throw new InvitationFailure("INVITATION_INVALID");
        }
        String tokenHash = digest(token);
        var target = invitations.locate(tokenHash);
        if (target == null) throw new InvitationFailure("INVITATION_INVALID");
        UUID identityId = transactions.execute(status -> {
            invitations.setTarget(target.tenantId());
            Instant now = clock.instant();
            if (!invitations.lockAccessibleTenant(target.tenantId(), now))
                throw new InvitationFailure("TENANT_NOT_ACCESSIBLE");
            Invitation current = invitations.lockInvitation(target.tenantId(), target.invitationId());
            if (current == null || !tokenHash.equals(current.tokenHash()))
                throw new InvitationFailure("INVITATION_INVALID");
            if (current.status() == Invitation.Status.REVOKED)
                throw new InvitationFailure("INVITATION_REVOKED");
            if (current.status() == Invitation.Status.ACCEPTED)
                throw new InvitationFailure("INVITATION_ALREADY_ACCEPTED");
            if (!current.pendingAt(now))
                throw new InvitationFailure("INVITATION_EXPIRED");
            UUID invited = invitations.invitedIdentity(target.tenantId(), target.invitationId());
            if (invitations.membershipExists(target.tenantId(), invited))
                throw new InvitationFailure("MEMBERSHIP_ALREADY_EXISTS");
            return invited;
        });
        return setups.request(requestId, identityId);
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
