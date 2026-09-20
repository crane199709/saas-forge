package io.saas.forge.iam.application.authentication;

import static io.saas.forge.iam.application.authentication.ConsoleSessionException.Code.*;
import static io.saas.forge.iam.domain.session.RefreshTokenFamilyPurpose.USER_PLATFORM;
import static io.saas.forge.iam.domain.session.RefreshTokenFamilyPurpose.USER_TENANT;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.session.*;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 统一 Console 的工作视图选择与切换。目标只按服务端权威 Platform Role 与 Accessible Membership
 * 解析，不接受调用方提交 tenantId；提交后撤销旧上下文全部未过期 Token 并等待 refresh 建立新上下文。
 */
public final class ConsoleContextSelectionService {
    private final ConsoleSessionAccess access;
    private final ConsoleSessionRepository slots;
    private final RefreshTokenFamilyRepository families;
    private final AccessTokenIssuanceRepository issuances;
    private final ConsoleSessionAuthority authority;
    private final ConsoleSessionTermination termination;
    private final RevocationIndex revocations;
    private final OutboxEventRepository outbox;
    private final TenantContextSwitchedEventFactory switchedEvents;
    private final UserTokenIssuanceFence issuanceFence;
    private final TransactionTemplate transactions;
    private final UuidV7Generator keys;
    private final Clock clock;

    public ConsoleContextSelectionService(ConsoleSessionAccess access, ConsoleSessionRepository slots,
            RefreshTokenFamilyRepository families, AccessTokenIssuanceRepository issuances,
            ConsoleSessionAuthority authority, ConsoleSessionTermination termination, RevocationIndex revocations,
            OutboxEventRepository outbox, TenantContextSwitchedEventFactory switchedEvents,
            UserTokenIssuanceFence issuanceFence, TransactionTemplate transactions, UuidV7Generator keys,
            Clock clock) {
        this.access = access; this.slots = slots; this.families = families; this.issuances = issuances;
        this.authority = authority; this.termination = termination; this.revocations = revocations;
        this.outbox = outbox; this.switchedEvents = switchedEvents; this.issuanceFence = issuanceFence;
        this.transactions = transactions; this.keys = keys; this.clock = clock;
    }

    /** 返回权威 Slot revision；实际切换后的下一次操作必须先 refresh 取得新上下文 Token。 */
    public long select(String locator, String refresh, String revision, UUID key, Target target) {
        ConsoleSessionAccess.requireKey(key);
        String fingerprint = target.fingerprint();
        long[] lockedRevision = {0};
        ConsoleOperation operation;
        try {
            operation = transactions.execute(status -> {
                ConsoleSlot slot = access.lock(locator);
                lockedRevision[0] = slot.revision();
                Optional<ConsoleOperation> previous = slots.operation(slot.id(), key);
                if (previous.isPresent()) return replay(previous.get(), fingerprint);
                access.requireRevision(slot, revision);
                access.requireReadable(slot);
                RefreshTokenFamily family = access.family(slot, refresh, false);
                if (family.purpose() == RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE)
                    throw new ConsoleSessionException(INITIAL_CREDENTIAL_RESTRICTED);
                ConsoleSessionAuthority.Contexts contexts = authority.contexts(family.identityId());
                if (!currentAuthorized(family, contexts)) throw new ConsoleSessionException(CURRENT_CONTEXT_REVOKED);
                Target resolved = resolve(target, contexts);
                ConsoleOperation created = new ConsoleOperation(slot.id(), key, family.id(),
                        ConsoleOperation.Kind.SELECT_CONTEXT, fingerprint, null);
                slots.begin(created);
                if (sameContext(family, resolved)) {
                    slots.complete(slot.id(), key, slot.revision());
                    return new ConsoleOperation(slot.id(), key, family.id(), created.kind(), fingerprint,
                            slot.revision());
                }
                issuanceFence.assertIssuable(resolved.membershipId(), resolved.tenantId());
                commit(slot, family, resolved);
                return created;
            });
        } catch (ConsoleSessionException failure) {
            if (failure.code() == CURRENT_CONTEXT_REVOKED)
                termination.logout(locator, "\"" + lockedRevision[0] + "\"", keys.next());
            throw failure;
        }
        if (operation.completed()) return operation.resultRevision();
        return transactions.execute(status -> {
            ConsoleSlot slot = access.lock(locator);
            ConsoleOperation current = slots.operation(slot.id(), key)
                    .orElseThrow(() -> new IllegalStateException("Console 上下文选择记录不存在"));
            if (current.completed()) return current.resultRevision();
            if (!current.familyId().equals(slot.familyId())
                    || slot.transition() != ConsoleSlot.Transition.SWITCH_PENDING)
                throw new ConsoleSessionException(SESSION_REVISION_CHANGED);
            // 持有 Slot 锁交付撤销，避免并发重放在另一请求完成并刷新后撤销新上下文 Token。
            // Redis 失败仅回滚本次完成标记；上一事务的 SWITCH_PENDING 与持久撤销仍可恢复。
            deliver(current.familyId());
            ConsoleSlot ready = slot.transitionTo(ConsoleSlot.Transition.CONTEXT_REFRESH_REQUIRED);
            slots.save(ready);
            slots.complete(slot.id(), key, ready.revision());
            return ready.revision();
        });
    }

    /** 同键重放只返回原结果；未完成的持久切换按原键恢复撤销交付。 */
    private static ConsoleOperation replay(ConsoleOperation existing, String fingerprint) {
        if (existing.kind() != ConsoleOperation.Kind.SELECT_CONTEXT
                || !existing.fingerprint().equals(fingerprint))
            throw new ConsoleSessionException(IDEMPOTENCY_KEY_REUSED);
        return existing;
    }

    private static boolean currentAuthorized(RefreshTokenFamily family, ConsoleSessionAuthority.Contexts contexts) {
        return switch (family.purpose()) {
            case USER_PLATFORM -> contexts.platform();
            case USER_TENANT -> contexts.companies().stream().anyMatch(company ->
                    company.membershipId().equals(family.membershipId())
                            && company.tenantId().equals(family.tenantId()));
            case USER_TENANT_SELECTION -> true;
            case INITIAL_PASSWORD_CHANGE -> false;
        };
    }

    private static Target resolve(Target target, ConsoleSessionAuthority.Contexts contexts) {
        if (target.purpose() == USER_PLATFORM) {
            if (!contexts.platform()) throw new ConsoleSessionException(TARGET_CONTEXT_UNAVAILABLE);
            return Target.platform();
        }
        return contexts.companies().stream()
                .filter(company -> company.membershipId().equals(target.membershipId()))
                .findFirst()
                .map(company -> Target.tenant(company.membershipId(), company.tenantId()))
                .orElseThrow(() -> new ConsoleSessionException(TARGET_CONTEXT_UNAVAILABLE));
    }

    private static boolean sameContext(RefreshTokenFamily family, Target target) {
        return switch (family.purpose()) {
            case USER_PLATFORM -> target.purpose() == USER_PLATFORM;
            case USER_TENANT -> target.purpose() == USER_TENANT
                    && family.membershipId().equals(target.membershipId());
            default -> false;
        };
    }

    /** 数据库权威事实先提交并进入 SWITCH_PENDING；旧 Token 交付确认后才允许 refresh。 */
    private void commit(ConsoleSlot slot, RefreshTokenFamily family, Target target) {
        var now = clock.instant();
        RefreshTokenFamilyContextChange change = families.switchWorkContext(
                family.id(), target.purpose(), target.membershipId(), target.tenantId());
        if (change.status() != RefreshTokenFamilyContextChange.Status.CHANGED) {
            throw new IllegalStateException("Console 工作视图切换未生效");
        }
        for (AccessTokenIssuance issuance : issuances.findUnexpiredByFamilyId(family.id(), now)) {
            issuances.revoke(issuance.jti(), now, "CONSOLE_CONTEXT_SELECTED");
        }
        slots.save(slot.transitionTo(ConsoleSlot.Transition.SWITCH_PENDING));
        // 平台工作视图没有 Tenant 上下文；只有 Tenant 之间的切换才有既有审计事实。
        if (family.membershipId() != null && target.membershipId() != null) {
            outbox.append(switchedEvents.create(family.id(), family.identityId(), family.membershipId(),
                    target.membershipId(), target.tenantId(), now, null));
        }
    }

    private void deliver(UUID familyId) {
        var now = clock.instant();
        try {
            for (AccessTokenIssuance issuance : issuances.findUnexpiredByFamilyId(familyId, now)) {
                revocations.revokeJti(issuance.jti(), issuance.expiresAt(), now);
            }
            if (!revocations.isReady()) throw new ConsoleSessionException(SESSION_TRANSITION_PENDING);
        } catch (RevocationIndexUnavailableException unavailable) {
            throw new ConsoleSessionException(SESSION_TRANSITION_PENDING);
        }
    }

    /** 请求级目标只允许 PLATFORM 或无 tenantId 的 membershipId。 */
    public record Target(RefreshTokenFamilyPurpose purpose, UUID membershipId, UUID tenantId) {
        public Target {
            if (purpose != USER_PLATFORM && purpose != USER_TENANT) {
                throw new IllegalArgumentException("目标工作视图只能是平台管理或 Tenant");
            }
            if ((purpose == USER_PLATFORM) != (membershipId == null)) {
                throw new IllegalArgumentException("平台目标必须且只能没有 Membership");
            }
        }

        public static Target platform() { return new Target(USER_PLATFORM, null, null); }

        public static Target tenant(UUID membershipId) { return new Target(USER_TENANT, membershipId, null); }

        public static Target tenant(UUID membershipId, UUID tenantId) {
            return new Target(USER_TENANT, membershipId, tenantId);
        }

        public String fingerprint() { return membershipId == null ? "PLATFORM" : "TENANT:" + membershipId; }
    }
}
