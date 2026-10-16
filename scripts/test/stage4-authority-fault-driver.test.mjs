import assert from 'node:assert/strict';
import test from 'node:test';
import { runInvitationCompensation, runClosure, runClosureRecovery,
  validateFaultScenario } from '../stage4-authority-fault-driver.mjs';

test('invitation fault is removed even when acceptance never reaches compensation', async () => {
  const events = [];
  const adapter = {
    readQuotaUsage: async () => 1,
    installInvitationFault: async () => { events.push('installed'); },
    removeInvitationFault: async () => { events.push('removed'); },
    waitForInvitationState: async () => { throw new Error('ACCEPTANCE_TIMEOUT'); }
  };
  await assert.rejects(runInvitationCompensation(adapter, { tenantId: 'tenant', invitationId: 'invite' }),
    /ACCEPTANCE_TIMEOUT/);
  assert.deepEqual(events, ['installed', 'removed']);
});

test('compensated invitation releases its seat before a fresh acceptance can succeed', async () => {
  const events = [];
  const snapshots = [
    { state: 'COMPENSATED', consumeId: 'consume-1', releaseId: 'release-1',
      consumeOutcome: 'SUCCESS', releaseOutcome: 'SUCCESS', usage: 1,
      membershipCount: 0, invitationStatus: 'PENDING', acceptedFacts: 0 },
    { state: 'SUCCEEDED', consumeId: 'consume-2', releaseId: 'release-2',
      consumeOutcome: 'SUCCESS', releaseOutcome: null, usage: 2,
      membershipCount: 1, invitationStatus: 'ACCEPTED', acceptedFacts: 1 }
  ];
  const adapter = {
    readQuotaUsage: async () => 1,
    installInvitationFault: async () => { events.push('installed'); },
    removeInvitationFault: async () => { events.push('removed'); },
    waitForInvitationState: async (_target, state) => {
      events.push(state);
      return snapshots.shift();
    },
    announceRetryReady: async () => { events.push('retry-ready'); }
  };
  const result = await runInvitationCompensation(adapter, { tenantId: 'tenant', invitationId: 'invite' });
  assert.deepEqual(events, ['installed', 'COMPENSATED', 'removed', 'retry-ready', 'SUCCEEDED']);
  assert.deepEqual(result, { initialUsage: 1, compensated: true, retried: true });
});

test('closure recovery removes a tenant-scoped IAM fault after a failed window', async () => {
  const events = [];
  const adapter = {
    closeTenant: async () => { events.push('close'); return { status: 503, code: 'TENANT_CLOSURE_PENDING' }; },
    installIamFault: async () => { events.push('install'); },
    waitForLifecycleState: async () => { throw new Error('RECOVERY_TIMEOUT'); },
    removeIamFault: async () => { events.push('remove'); }
  };
  await assert.rejects(runClosureRecovery(adapter, 'tenant', 'key-1', 'key-2'), /RECOVERY_TIMEOUT/);
  assert.deepEqual(events, ['install', 'close', 'remove']);
});

test('closure recovery continues the original fenced request after the IAM fault clears', async () => {
  const events = [];
  const adapter = {
    closeTenant: async () => { events.push('close'); return { status: 503, code: 'TENANT_CLOSURE_PENDING' }; },
    installIamFault: async () => { events.push('install'); },
    waitForLifecycleState: async () => ({ state: 'RECOVERY_REQUIRED',
      requestId: 'original-request', fenceEstablished: true }),
    removeIamFault: async () => { events.push('remove'); },
    recoverClosure: async () => ({ status: 503, code: 'TENANT_CLOSURE_RECOVERY_PENDING' }),
    replayRecovery: async () => { events.push('replay');
      return { status: 200, body: { status: 'CLOSED' } }; },
    waitForClosed: async () => ({ status: 'CLOSED', requestId: 'original-request',
      closedFacts: 1, oldTokenStatus: 401 })
  };
  const result = await runClosureRecovery(adapter, 'tenant', 'key-1', 'key-2');
  assert.deepEqual(events, ['install', 'close', 'remove', 'replay']);
  assert.deepEqual(result, { recovered: true, originalRequestPreserved: true,
    closedFacts: 1, oldTokenStatus: 401 });
});

test('fault driver rejects protected or cross-round tenants before mutation', () => {
  const a = '0199d1b0-1111-7000-8000-000000000001';
  const b = '0199d1b0-1111-7000-8000-000000000002';
  const c = '0199d1b0-1111-7000-8000-000000000003';
  const handoff = { schemaVersion: 1, status: 'ready', runId: a,
    startedAt: '2026-10-16T00:00:00Z', readyAt: '2026-10-16T00:00:00Z',
    expiresAt: '2026-10-25T00:00:00Z', apiOrigin: 'https://api.saas.forge.test',
    isolation: { kind: 'fresh-compose', project: `sf-acceptance-${a}` },
    backend: { dirty: false, commit: 'a'.repeat(40), images: [] } };
  const scenario = { runId: a, protectedTenantIds: [a, b],
    invitation: { tenantId: c, invitationId: c,
      disposableDisplayName: `Stage4 Fault ${a} Invitation` } };
  assert.doesNotThrow(() => validateFaultScenario(handoff, scenario, 'invitation'));
  assert.throws(() => validateFaultScenario(handoff,
    { ...scenario, invitation: { ...scenario.invitation, tenantId: a } }, 'invitation'));
  assert.throws(() => validateFaultScenario(handoff,
    { ...scenario, runId: b }, 'invitation'));
  assert.throws(() => validateFaultScenario(handoff,
    { ...scenario, invitation: { ...scenario.invitation, disposableDisplayName: 'Tenant A' } },
    'invitation'));
});

test('ordinary closure retains one fact and rejects the prior Tenant token', async () => {
  let replayed = false;
  const adapter = {
    closeTenant: async () => ({ status: 503, code: 'TENANT_CLOSURE_PENDING' }),
    replayClosure: async () => { replayed = true;
      return { status: 200, body: { status: 'CLOSED' } }; },
    waitForClosed: async () => ({ status: 'CLOSED', closedFacts: 1, oldTokenStatus: 401 })
  };
  assert.deepEqual(await runClosure(adapter, 'tenant', 'key'),
    { closed: true, closedFacts: 1, oldTokenStatus: 401 });
  assert.equal(replayed, true);
});
