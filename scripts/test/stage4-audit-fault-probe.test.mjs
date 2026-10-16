import assert from 'node:assert/strict';
import test from 'node:test';
import { validateRetryEvidence, validateRollbackEvidence } from '../stage4-audit-fault-probe.mjs';

const eventId = '0199d1b0-1111-7000-8000-000000000001';

test('a published retry keeps one committed event and one Audit Record', () => {
  const outbox = { eventId, type: 'com.saas.forge.example.project.updated.v1',
    resourceId: '0199d1b0-1111-7000-8000-000000000002', attemptCount: 2,
    publishedAt: '2026-10-16T04:00:00Z', lastFailure: null, snapshotSha256: 'a'.repeat(64) };
  const audit = { recordCount: 1, consumedCount: 1, sourceEventId: eventId,
    action: 'PROJECT_UPDATED', result: 'SUCCESS' };
  assert.doesNotThrow(() => validateRetryEvidence(outbox, audit, outbox.resourceId));
  assert.throws(() => validateRetryEvidence({ ...outbox, attemptCount: 1 }, audit, outbox.resourceId));
  assert.throws(() => validateRetryEvidence(outbox, { ...audit, recordCount: 2 }, outbox.resourceId));
  assert.throws(() => validateRetryEvidence(outbox, { ...audit, sourceEventId: null }, outbox.resourceId));
});

test('a failed Project transaction leaves its Project, idempotency and success facts unchanged', () => {
  const before = { outboxCount: 3, auditCount: 3 };
  const original = { id: '0199d1b0-1111-7000-8000-000000000002', name: 'Original', version: 2 };
  assert.doesNotThrow(() => validateRollbackEvidence(before, before, 0, original, original));
  assert.throws(() => validateRollbackEvidence(before,
    { outboxCount: 4, auditCount: 3 }, 0, original, original));
  assert.throws(() => validateRollbackEvidence(before, before, 1, original, original));
  assert.throws(() => validateRollbackEvidence(before, before, 0, original,
    { ...original, name: 'Uncommitted' }));
});
