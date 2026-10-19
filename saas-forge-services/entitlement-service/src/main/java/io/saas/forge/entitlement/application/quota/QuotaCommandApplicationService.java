package io.saas.forge.entitlement.application.quota;

import io.saas.forge.entitlement.application.bootstrap.EntitlementEventFactory;
import io.saas.forge.entitlement.domain.outbox.OutboxEventRepository;
import io.saas.forge.entitlement.domain.quota.QuotaOperation;
import io.saas.forge.entitlement.domain.quota.QuotaOperationAction;
import io.saas.forge.entitlement.domain.quota.QuotaOperationOutcome;
import io.saas.forge.entitlement.domain.quota.QuotaOperationPurpose;
import io.saas.forge.entitlement.domain.quota.QuotaOperationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class QuotaCommandApplicationService {
    private static final String INITIALIZATION_QUOTA = "max_users";
    private static final int INITIALIZATION_AMOUNT = 1;

    private final QuotaOperationRepository operations;
    private final OutboxEventRepository outboxEvents;
    private final EntitlementEventFactory eventFactory;
    private final Clock clock;

    public QuotaCommandApplicationService(
            QuotaOperationRepository operations,
            OutboxEventRepository outboxEvents,
            EntitlementEventFactory eventFactory,
            Clock clock) {
        this.operations = operations;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = QuotaCommandException.class)
    public QuotaCommandResult consume(
            UUID callerClientId,
            UUID tenantId,
            String quotaCode,
            int amount,
            UUID operationId,
            QuotaOperationPurpose purpose) {
        return execute(callerClientId, tenantId, quotaCode, amount, operationId,
                purpose, QuotaOperationAction.CONSUME);
    }

    @Transactional(noRollbackFor = QuotaCommandException.class)
    public QuotaCommandResult release(
            UUID callerClientId,
            UUID tenantId,
            String quotaCode,
            int amount,
            UUID operationId,
            QuotaOperationPurpose purpose) {
        return execute(callerClientId, tenantId, quotaCode, amount, operationId,
                purpose, QuotaOperationAction.RELEASE);
    }

    /** Usage、不可变 Operation 结果与成功事件必须共享事务；业务失败结果同样提交以供重放。 */
    private QuotaCommandResult execute(
            UUID callerClientId,
            UUID tenantId,
            String quotaCode,
            int amount,
            UUID operationId,
            QuotaOperationPurpose purpose,
            QuotaOperationAction action) {
        validate(callerClientId, tenantId, quotaCode, amount, operationId, purpose, action);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String fingerprint = fingerprint(tenantId, quotaCode, amount, operationId, purpose, action);
        operations.setOperationTarget(tenantId);
        QuotaOperation candidate = new QuotaOperation(
                operationId, callerClientId, tenantId, quotaCode, amount, action, purpose,
                fingerprint, null, null, null, now, null);
        if (!operations.claim(candidate)) {
            return replay(operationId, fingerprint);
        }

        UUID quotaDefinitionId = operations.findQuotaDefinitionId(quotaCode).orElse(null);
        if (quotaDefinitionId == null) {
            fail(operationId, QuotaOperationOutcome.QUOTA_DEFINITION_NOT_FOUND, null, null, now, false);
        }
        Integer limit = (action == QuotaOperationAction.CONSUME
                ? operations.findCurrentLimit(tenantId, quotaDefinitionId, now)
                : operations.findGrantedLimit(tenantId, quotaDefinitionId)).orElse(null);
        if (limit == null) {
            fail(operationId, QuotaOperationOutcome.SUBSCRIPTION_REQUIRED, null, null, now, false);
        }
        operations.initializeUsage(tenantId, quotaDefinitionId, now);
        var adjusted = action == QuotaOperationAction.CONSUME
                ? operations.consume(tenantId, quotaDefinitionId, amount, limit, now)
                : operations.release(tenantId, quotaDefinitionId, amount, now);
        if (adjusted.isEmpty()) {
            int usage = operations.currentUsage(tenantId, quotaDefinitionId);
            QuotaOperationOutcome outcome = action == QuotaOperationAction.CONSUME
                    ? QuotaOperationOutcome.QUOTA_EXCEEDED
                    : QuotaOperationOutcome.QUOTA_RELEASE_UNDERFLOW;
            fail(operationId, outcome, usage, limit, now, false);
        }

        int usage = adjusted.orElseThrow();
        operations.complete(operationId, QuotaOperationOutcome.SUCCESS, usage, limit, now);
        outboxEvents.append(eventFactory.quotaOperation(
                tenantId, quotaDefinitionId, operationId, amount, purpose, action, now));
        return new QuotaCommandResult(usage, limit, false);
    }

    private QuotaCommandResult replay(UUID operationId, String fingerprint) {
        QuotaOperation existing = operations.find(operationId)
                .orElseThrow(QuotaOperationIdReusedException::new);
        if (!existing.requestFingerprint().equals(fingerprint) || !existing.completed()) {
            throw new QuotaOperationIdReusedException();
        }
        if (existing.outcome() != QuotaOperationOutcome.SUCCESS) {
            throw new QuotaCommandException(
                    existing.outcome(), existing.usage(), existing.limit(), true);
        }
        return new QuotaCommandResult(existing.usage(), existing.limit(), true);
    }

    private void fail(
            UUID operationId,
            QuotaOperationOutcome outcome,
            Integer usage,
            Integer limit,
            Instant at,
            boolean replayed) {
        operations.complete(operationId, outcome, usage, limit, at);
        throw new QuotaCommandException(outcome, usage, limit, replayed);
    }

    private static void validate(
            UUID callerClientId,
            UUID tenantId,
            String quotaCode,
            int amount,
            UUID operationId,
            QuotaOperationPurpose purpose,
            QuotaOperationAction action) {
        requireUuidV7(callerClientId, "调用方 Client ID");
        requireUuidV7(tenantId, "Tenant ID");
        requireUuidV7(operationId, "Quota Operation ID");
        if (!INITIALIZATION_QUOTA.equals(quotaCode)
                || amount != INITIALIZATION_AMOUNT
                || (purpose != QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION
                    && purpose != QuotaOperationPurpose.INVITATION_ACCEPTANCE
                    && purpose != QuotaOperationPurpose.MEMBERSHIP_DISABLE
                    && purpose != QuotaOperationPurpose.MEMBERSHIP_ENABLE)
                || (purpose == QuotaOperationPurpose.MEMBERSHIP_DISABLE
                    && action != QuotaOperationAction.RELEASE)) {
            throw new IllegalArgumentException("只允许占用或释放一个 max_users");
        }
    }

    private static String fingerprint(
            UUID tenantId,
            String quotaCode,
            int amount,
            UUID operationId,
            QuotaOperationPurpose purpose,
            QuotaOperationAction action) {
        String canonical = action + "\n" + tenantId + "\n" + quotaCode + "\n"
                + amount + "\n" + operationId + "\n" + purpose;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private static void requireUuidV7(UUID value, String field) {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException(field + " 必须是 UUIDv7");
        }
    }
}
