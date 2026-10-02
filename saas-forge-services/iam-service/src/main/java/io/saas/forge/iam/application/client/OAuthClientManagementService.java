package io.saas.forge.iam.application.client;

import io.saas.forge.iam.application.authentication.UuidV7Generator;
import io.saas.forge.iam.application.authentication.RevocationIndex;
import io.saas.forge.iam.application.authentication.RevocationIndexUnavailableException;
import io.saas.forge.iam.application.authentication.TokenRevocationStatusUnavailableException;
import io.saas.forge.iam.domain.client.OAuthClient;
import io.saas.forge.iam.domain.client.OAuthClientManagementOperation;
import io.saas.forge.iam.domain.client.OAuthClientManagementOperationRepository;
import io.saas.forge.iam.domain.client.OAuthClientRepository;
import io.saas.forge.iam.domain.client.OAuthClientSecretRotationException;
import io.saas.forge.iam.domain.client.OAuthClientSecretRecoveryException;
import io.saas.forge.iam.domain.client.OAuthScope;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.shared.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class OAuthClientManagementService {
    private static final java.time.Duration RECOVERY_WINDOW = java.time.Duration.ofMinutes(10);
    private final OAuthClientRepository clients;
    private final OAuthClientManagementOperationRepository operations;
    private final OutboxEventRepository outbox;
    private final OAuthClientCreatedEventFactory events;
    private final ClientSecretRotatedEventFactory rotationEvents;
    private final ClientSecretIssuanceRecoveredEventFactory recoveryEvents;
    private final OAuthClientRevokedEventFactory revocationEvents;
    private final RevocationIndex revocations;
    private final ClientSecretIssuer secrets;
    private final UuidV7Generator ids;
    private final Clock clock;

    public OAuthClientManagementService(
            OAuthClientRepository clients,
            OAuthClientManagementOperationRepository operations,
            OutboxEventRepository outbox,
            OAuthClientCreatedEventFactory events,
            ClientSecretRotatedEventFactory rotationEvents,
            ClientSecretIssuanceRecoveredEventFactory recoveryEvents,
            OAuthClientRevokedEventFactory revocationEvents,
            RevocationIndex revocations,
            ClientSecretIssuer secrets,
            UuidV7Generator ids,
            Clock clock) {
        this.clients = clients;
        this.operations = operations;
        this.outbox = outbox;
        this.events = events;
        this.rotationEvents = rotationEvents;
        this.recoveryEvents = recoveryEvents;
        this.revocationEvents = revocationEvents;
        this.revocations = revocations;
        this.secrets = secrets;
        this.ids = ids;
        this.clock = clock;
    }

    /** Client、Secret 摘要、永久操作终态与事件必须在同一事务提交后才展示 Secret。 */
    @Transactional
    public OAuthClientSecretResult create(
            UUID actorIdentityId,
            UUID idempotencyKey,
            String displayName,
            Set<OAuthScope> allowedScopes,
            String traceId) {
        requireUuidV7(idempotencyKey);
        Sha256Digest fingerprint = fingerprint(displayName, allowedScopes);
        if (!operations.tryLock(actorIdentityId, idempotencyKey)) {
            throw OAuthClientManagementException.inProgress();
        }
        operations.find(actorIdentityId, idempotencyKey).ifPresent(existing -> {
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw OAuthClientManagementException.keyReused();
            }
            throw OAuthClientManagementException.secretAlreadyRevealed(existing.clientId());
        });

        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        OAuthClient prepared = OAuthClient.registerManaged(displayName, allowedScopes, createdAt);
        ClientSecretIssuer.IssuedClientSecret issued = secrets.issue();
        var creation = clients.create(prepared, issued.digest(), createdAt);
        OAuthClient client = creation.client();
        OAuthClientManagementOperation operation = new OAuthClientManagementOperation(
                ids.next(), actorIdentityId, idempotencyKey, "CREATE", client.id(), fingerprint,
                null, creation.initialSecret().id(),
                "SUCCEEDED", 201, createdAt);
        operations.append(operation);
        outbox.append(events.create(client, operation, actorIdentityId, createdAt, traceId));
        return new OAuthClientSecretResult(client, issued.plaintext());
    }

    @Transactional(readOnly = true)
    public OAuthClient get(UUID clientId) {
        return clients.findById(clientId).orElseThrow(OAuthClientManagementException::notFound);
    }

    @Transactional
    public OAuthClientSecretResult recoverOperation(UUID actorIdentityId, UUID idempotencyKey,
            UUID operationId, String traceId) {
        var operation = operations.findById(actorIdentityId, operationId)
                .orElseThrow(OAuthClientManagementException::recoveryNotAllowed);
        return recover(actorIdentityId, idempotencyKey, operation.clientId(), operation.idempotencyKey(), traceId);
    }

    /** 旧 Secret 截止时间、新 Secret、操作终态与事件在同一事务中提交。 */
    @Transactional
    public OAuthClientSecretResult rotate(
            UUID actorIdentityId, UUID idempotencyKey, UUID clientId, String traceId) {
        requireUuidV7(idempotencyKey);
        Sha256Digest fingerprint = fingerprint("ROTATE", clientId == null ? "" : clientId.toString());
        if (!operations.tryLock(actorIdentityId, idempotencyKey)) {
            throw OAuthClientManagementException.inProgress();
        }
        operations.find(actorIdentityId, idempotencyKey).ifPresent(existing -> {
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw OAuthClientManagementException.keyReused();
            }
            throw OAuthClientManagementException.secretAlreadyRevealed(existing.clientId());
        });

        Instant rotatedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ClientSecretIssuer.IssuedClientSecret issued = secrets.issue();
        try {
            var rotatedSecret = clients.rotate(clientId, issued.digest(), rotatedAt);
            OAuthClient client = clients.findById(clientId)
                    .orElseThrow(() -> new IllegalStateException("OAuth Client 轮换后查询失败"));
            OAuthClientManagementOperation operation = new OAuthClientManagementOperation(
                    ids.next(), actorIdentityId, idempotencyKey, "ROTATE", client.id(), fingerprint,
                    null, rotatedSecret.id(), "SUCCEEDED", 200, rotatedAt);
            operations.append(operation);
            outbox.append(rotationEvents.create(client, operation, actorIdentityId, rotatedAt, traceId));
            return new OAuthClientSecretResult(client, issued.plaintext());
        } catch (OAuthClientSecretRotationException exception) {
            throw switch (exception.reason()) {
                case CLIENT_NOT_FOUND -> OAuthClientManagementException.notFound();
                case CLIENT_REVOKED -> OAuthClientManagementException.revoked();
                case OVERLAP_ACTIVE -> OAuthClientManagementException.rotationOverlapActive();
            };
        }
    }

    /** 原操作资格、Secret 替代、永久终态与恢复事实必须共享同一事务。 */
    @Transactional
    public OAuthClientSecretResult recover(
            UUID actorIdentityId,
            UUID idempotencyKey,
            UUID clientId,
            UUID originalIdempotencyKey,
            String traceId) {
        requireUuidV7(idempotencyKey);
        if (originalIdempotencyKey == null || originalIdempotencyKey.version() != 7
                || originalIdempotencyKey.equals(idempotencyKey)) {
            throw OAuthClientManagementException.recoveryRequestInvalid();
        }
        Sha256Digest fingerprint = fingerprint(
                "RECOVER", clientId + "\n" + originalIdempotencyKey);
        if (!operations.tryLock(actorIdentityId, idempotencyKey)) {
            throw OAuthClientManagementException.inProgress();
        }
        operations.find(actorIdentityId, idempotencyKey).ifPresent(existing -> {
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw OAuthClientManagementException.keyReused();
            }
            throw OAuthClientManagementException.recoveryNotAllowed();
        });

        Instant recoveredAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        OAuthClientManagementOperation original = operations
                .find(actorIdentityId, originalIdempotencyKey)
                .filter(value -> ("CREATE".equals(value.operationType()) || "ROTATE".equals(value.operationType()))
                        && "SUCCEEDED".equals(value.outcome())
                        && value.clientId().equals(clientId)
                        && value.secretRecordId() != null
                        && !recoveredAt.isBefore(value.completedAt())
                        && !recoveredAt.isAfter(value.completedAt().plus(RECOVERY_WINDOW)))
                .orElseThrow(OAuthClientManagementException::recoveryNotAllowed);
        if (!operations.tryLockRecovery(original.id())) {
            throw OAuthClientManagementException.inProgress();
        }
        if (operations.findSuccessfulRecovery(original.id()).isPresent()) {
            throw OAuthClientManagementException.recoveryNotAllowed();
        }

        ClientSecretIssuer.IssuedClientSecret issued = secrets.issue();
        try {
            var replacement = clients.recover(clientId, original.secretRecordId(), issued.digest(), recoveredAt);
            OAuthClient client = clients.findById(clientId)
                    .orElseThrow(() -> new IllegalStateException("OAuth Client 恢复后查询失败"));
            OAuthClientManagementOperation operation = new OAuthClientManagementOperation(
                    ids.next(), actorIdentityId, idempotencyKey, "RECOVER", client.id(), fingerprint,
                    original.id(), replacement.id(), "SUCCEEDED", 200, recoveredAt);
            operations.append(operation);
            outbox.append(recoveryEvents.create(client, operation, actorIdentityId, recoveredAt, traceId));
            return new OAuthClientSecretResult(client, issued.plaintext());
        } catch (OAuthClientSecretRecoveryException exception) {
            throw switch (exception.reason()) {
                case CLIENT_NOT_FOUND -> OAuthClientManagementException.notFound();
                case CLIENT_REVOKED -> OAuthClientManagementException.revoked();
                case SECRET_NOT_RECOVERABLE -> OAuthClientManagementException.recoveryNotAllowed();
            };
        }
    }

    /** Redis 必须先拒绝 Client；数据库失败时保留额外拒绝，重试继续固定权威事实。 */
    @Transactional
    public void revoke(
            UUID actorIdentityId, UUID idempotencyKey, UUID clientId, String traceId) {
        requireUuidV7(idempotencyKey);
        OAuthClient current = clients.findById(clientId)
                .orElseThrow(OAuthClientManagementException::notFound);
        try {
            revocations.revokeClient(clientId);
        } catch (RevocationIndexUnavailableException unavailable) {
            throw new TokenRevocationStatusUnavailableException();
        }
        if (current.status() == io.saas.forge.iam.domain.client.OAuthClientStatus.REVOKED) return;

        Sha256Digest fingerprint = fingerprint("REVOKE", clientId.toString());
        if (!operations.tryLock(actorIdentityId, idempotencyKey)) {
            throw OAuthClientManagementException.inProgress();
        }
        var existing = operations.find(actorIdentityId, idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().requestFingerprint().equals(fingerprint)) {
                throw OAuthClientManagementException.keyReused();
            }
            return;
        }

        Instant revokedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!clients.revoke(clientId, revokedAt)) return;
        OAuthClient client = clients.findById(clientId)
                .orElseThrow(() -> new IllegalStateException("OAuth Client 吊销后查询失败"));
        OAuthClientManagementOperation operation = new OAuthClientManagementOperation(
                ids.next(), actorIdentityId, idempotencyKey, "REVOKE", client.id(), fingerprint,
                null, null,
                "SUCCEEDED", 204, revokedAt);
        operations.append(operation);
        outbox.append(revocationEvents.create(client, operation, actorIdentityId, revokedAt, traceId));
    }

    private static void requireUuidV7(UUID key) {
        if (key == null || key.version() != 7) throw OAuthClientManagementException.idempotencyInvalid();
    }

    private static Sha256Digest fingerprint(String displayName, Set<OAuthScope> scopes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, displayName == null ? "" : displayName);
            if (scopes != null) {
                scopes.stream().map(OAuthScope::value).sorted(Comparator.naturalOrder())
                        .forEach(value -> update(digest, value));
            }
            return Sha256Digest.of(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }

    private static Sha256Digest fingerprint(String operation, String target) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, operation);
            update(digest, target);
            return Sha256Digest.of(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    public record OAuthClientSecretResult(OAuthClient client, String clientSecret) {
    }
}
