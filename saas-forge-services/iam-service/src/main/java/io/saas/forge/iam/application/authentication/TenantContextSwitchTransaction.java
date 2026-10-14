package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import io.saas.forge.iam.domain.session.AccessTokenIssuance;
import io.saas.forge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saas.forge.iam.domain.session.RefreshTokenFamily;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyContextChange;
import io.saas.forge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saas.forge.iam.domain.session.TenantContextSwitchRepository;
import io.saas.forge.iam.domain.session.TenantContextSwitchStatus;
import io.saas.forge.iam.domain.session.TenantContextSwitchWorkflow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class TenantContextSwitchTransaction {
    private final TenantContextSwitchRepository workflows;
    private final RefreshTokenFamilyRepository families;
    private final AccessTokenIssuanceRepository issuances;
    private final RevocationIndex revocationIndex;
    private final OutboxEventRepository outboxEvents;
    private final TenantContextSwitchedEventFactory eventFactory;
    private final UserTokenIssuanceFence issuanceFence;

    public TenantContextSwitchTransaction(
            TenantContextSwitchRepository workflows,
            RefreshTokenFamilyRepository families,
            AccessTokenIssuanceRepository issuances,
            RevocationIndex revocationIndex,
            OutboxEventRepository outboxEvents,
            TenantContextSwitchedEventFactory eventFactory,
            UserTokenIssuanceFence issuanceFence) {
        this.workflows = workflows;
        this.families = families;
        this.issuances = issuances;
        this.revocationIndex = revocationIndex;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
        this.issuanceFence = issuanceFence;
    }

    @Transactional
    public void rejectCurrent(TenantContextSwitchWorkflow workflow, Instant rejectedAt) {
        RefreshTokenFamily family = families.lockById(workflow.familyId())
                .orElseThrow(() -> new IllegalStateException("Tenant Context Switch Family 不存在"));
        revokeFamily(family.id(), rejectedAt, "MEMBERSHIP_AUTHORIZATION_LOST");
        workflows.complete(workflow, TenantContextSwitchStatus.CURRENT_REJECTED, rejectedAt);
    }

    /** Redis 安全索引先于数据库成功；数据库回滚时保留 Redis 的额外拒绝。 */
    @Transactional
    public void switchContext(
            TenantContextSwitchWorkflow workflow,
            RefreshTokenFamily originalFamily,
            long expectedContextVersion,
            UUID targetMembershipId,
            UUID targetTenantId,
            Instant switchedAt,
            String traceId) {
        RefreshTokenFamily locked = families.lockById(originalFamily.id())
                .orElseThrow(() -> new IllegalStateException("Tenant Context Switch Family 不存在"));
        if (locked.contextVersion() != expectedContextVersion) {
            throw new IllegalStateException("Tenant Context Switch 的 Family Context 已变化");
        }
        issuanceFence.assertIdentityIssuable(locked.identityId());
        issuanceFence.assertIssuable(targetMembershipId, targetTenantId);
        List<AccessTokenIssuance> active = issuances.findUnexpiredByFamilyId(locked.id(), switchedAt);
        indexAccessTokens(active, switchedAt);
        RefreshTokenFamilyContextChange contextChange = families.switchTenantContext(
                locked.id(), expectedContextVersion, targetMembershipId, targetTenantId);
        if (contextChange.status() != RefreshTokenFamilyContextChange.Status.CHANGED) {
            throw new IllegalStateException("Tenant Context Switch 的 Family Context 已变化");
        }
        persistAccessTokenRevocations(active, switchedAt, "TENANT_CONTEXT_SWITCHED");
        workflows.markAwaitingRefresh(workflow, expectedContextVersion, switchedAt);
        outboxEvents.append(eventFactory.create(
                originalFamily.id(), originalFamily.identityId(), originalFamily.membershipId(),
                targetMembershipId, targetTenantId, switchedAt, traceId));
    }

    @Transactional
    public void rejectPostSwitchRefresh(UUID familyId, long contextVersion, Instant rejectedAt) {
        revokeFamily(familyId, rejectedAt, "POST_SWITCH_MEMBERSHIP_AUTHORIZATION_LOST");
        workflows.completePostSwitchRefresh(familyId, contextVersion, false, rejectedAt);
    }

    @Transactional
    public void complete(
            TenantContextSwitchWorkflow workflow, TenantContextSwitchStatus status, Instant completedAt) {
        workflows.complete(workflow, status, completedAt);
    }

    private void revokeFamily(UUID familyId, Instant at, String reason) {
        RefreshTokenFamily locked = families.lockById(familyId)
                .orElseThrow(() -> new IllegalStateException("Tenant Context Switch Family 不存在"));
        List<AccessTokenIssuance> active = issuances.findUnexpiredByFamilyId(locked.id(), at);
        revokeAccessTokens(active, at, reason);
        families.revokeById(locked.id(), at);
    }

    private void revokeAccessTokens(List<AccessTokenIssuance> active, Instant at, String reason) {
        indexAccessTokens(active, at);
        persistAccessTokenRevocations(active, at, reason);
    }

    private void indexAccessTokens(List<AccessTokenIssuance> active, Instant at) {
        for (AccessTokenIssuance issuance : active) {
            revocationIndex.revokeJti(issuance.jti(), issuance.expiresAt(), at);
        }
    }

    private void persistAccessTokenRevocations(List<AccessTokenIssuance> active, Instant at, String reason) {
        for (AccessTokenIssuance issuance : active) {
            issuances.revoke(issuance.jti(), at, reason);
        }
    }
}
