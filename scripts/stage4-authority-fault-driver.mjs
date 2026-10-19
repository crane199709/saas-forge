import assert from 'node:assert/strict';

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const RUN_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function validateFaultScenario(handoff, scenario, mode) {
  assert.ok(['invitation', 'closure', 'closure-recovery'].includes(mode), 'FAULT_MODE_INVALID');
  assert.equal(handoff.schemaVersion, 1);
  assert.equal(handoff.status, 'ready');
  assert.match(handoff.runId, RUN_ID);
  assert.equal(handoff.isolation?.kind, 'fresh-compose');
  assert.equal(handoff.isolation.project, `sf-acceptance-${handoff.runId}`);
  assert.equal(handoff.backend?.dirty, false);
  assert.match(handoff.backend.commit, /^[0-9a-f]{40}$/);
  assert.equal(new URL(handoff.apiOrigin).protocol, 'https:');
  assert.ok(Date.now() >= Date.parse(handoff.readyAt) && Date.now() < Date.parse(handoff.expiresAt));
  assert.equal(scenario.runId, handoff.runId);
  assert.ok(Array.isArray(scenario.protectedTenantIds) && scenario.protectedTenantIds.length >= 2);
  for (const id of scenario.protectedTenantIds) assert.match(id, UUID_V7);
  assert.equal(new Set(scenario.protectedTenantIds).size, scenario.protectedTenantIds.length);
  const target = mode === 'invitation' ? scenario.invitation :
    mode === 'closure' ? scenario.closure : scenario.closureRecovery;
  assert.ok(target && typeof target === 'object', 'FAULT_TARGET_REQUIRED');
  assert.match(target.tenantId, UUID_V7);
  assert.ok(!scenario.protectedTenantIds.includes(target.tenantId), 'PROTECTED_TENANT');
  assert.ok(typeof target.disposableDisplayName === 'string' &&
    target.disposableDisplayName.startsWith(`Stage4 Fault ${handoff.runId} `) &&
    target.disposableDisplayName.length <= 200, 'DISPOSABLE_TENANT_MARKER_REQUIRED');
  if (mode === 'invitation') assert.match(target.invitationId, UUID_V7);
  else {
    assert.equal(typeof scenario.platformTokenFile, 'string');
    assert.equal(typeof target.oldTenantTokenFile, 'string');
  }
  return target;
}

/** Stage 4 fault windows are always scoped to disposable Fresh tenants. */
export async function runInvitationCompensation(adapter, target) {
  const initialUsage = await adapter.readQuotaUsage(target);
  await adapter.installInvitationFault(target);
  let compensated;
  try {
    compensated = await adapter.waitForInvitationState(target, 'COMPENSATED');
  } finally {
    await adapter.removeInvitationFault(target);
  }
  assert.equal(compensated.state, 'COMPENSATED');
  assert.equal(compensated.consumeOutcome, 'SUCCESS');
  assert.equal(compensated.releaseOutcome, 'SUCCESS');
  assert.equal(compensated.usage, initialUsage);
  assert.equal(compensated.membershipCount, 0);
  assert.equal(compensated.invitationStatus, 'PENDING');
  assert.equal(compensated.acceptedFacts, 0);
  await adapter.announceRetryReady(target);
  const retried = await adapter.waitForInvitationState(target, 'SUCCEEDED');
  assert.equal(retried.state, 'SUCCEEDED');
  assert.notEqual(retried.consumeId, compensated.consumeId);
  assert.notEqual(retried.releaseId, compensated.releaseId);
  assert.equal(retried.consumeOutcome, 'SUCCESS');
  assert.equal(retried.usage, initialUsage + 1);
  assert.equal(retried.membershipCount, 1);
  assert.equal(retried.invitationStatus, 'ACCEPTED');
  assert.equal(retried.acceptedFacts, 1);
  return { initialUsage, compensated: true, retried: true };
}

export async function runClosureRecovery(adapter, tenantId, originalKey, recoveryKey) {
  await adapter.installIamFault();
  let pending;
  try {
    const first = await adapter.closeTenant(tenantId, originalKey);
    assert.equal(first.status, 503, 'CLOSURE_MUST_ENTER_PENDING_BEFORE_FAULT');
    assert.equal(first.code, 'TENANT_CLOSURE_PENDING');
    pending = await adapter.waitForLifecycleState(tenantId, 'RECOVERY_REQUIRED');
  } finally {
    await adapter.removeIamFault();
  }
  assert.equal(pending.state, 'RECOVERY_REQUIRED');
  assert.equal(pending.fenceEstablished, true);
  const recovery = await adapter.recoverClosure(tenantId, recoveryKey);
  assert.ok(recovery.status === 200 ||
    (recovery.status === 503 && recovery.code === 'TENANT_CLOSURE_RECOVERY_PENDING'));
  const closed = await adapter.waitForClosed(tenantId, recoveryKey);
  assert.equal(closed.status, 'CLOSED');
  assert.equal(closed.requestId, pending.requestId);
  assert.equal(closed.closedFacts, 1);
  assert.equal(closed.oldTokenStatus, 401);
  const replay = await adapter.replayRecovery(tenantId, recoveryKey);
  assert.equal(replay.status, 200);
  assert.equal(replay.body.status, 'CLOSED');
  return { recovered: true, originalRequestPreserved: true,
    closedFacts: 1, oldTokenStatus: 401 };
}

export async function runClosure(adapter, tenantId, key) {
  const first = await adapter.closeTenant(tenantId, key);
  assert.ok(first.status === 200 ||
    (first.status === 503 && first.code === 'TENANT_CLOSURE_PENDING'));
  const closed = await adapter.waitForClosed(tenantId, key);
  assert.equal(closed.status, 'CLOSED');
  assert.equal(closed.closedFacts, 1);
  assert.equal(closed.oldTokenStatus, 401);
  const replay = await adapter.replayClosure(tenantId, key);
  assert.equal(replay.status, 200);
  assert.equal(replay.body.status, 'CLOSED');
  return { closed: true, closedFacts: 1, oldTokenStatus: 401 };
}
