import assert from 'node:assert/strict';
import test from 'node:test';
import { validateLinkedFact } from '../stage4-audit-links.mjs';

const expected = {
  label: 'brand-upload',
  source: 'tenant-access',
  action: 'BRAND_ASSET_UPLOADED',
  resourceId: '0199d1b0-1111-7000-8000-000000000002',
  actorIdentityId: '0199d1b0-1111-7000-8000-000000000003',
  startedAt: '2026-10-15T00:00:00Z',
  finishedAt: '2026-10-15T00:00:03Z'
};
const audit = {
  source_event_id: '0199d1b0-1111-7000-8000-000000000001',
  source: 'urn:saas.forge:tenant-access-service',
  source_type: 'com.saas.forge.brand.asset-uploaded.v1',
  action: expected.action,
  result: 'SUCCESS',
  resource_id: expected.resourceId,
  actor_identity_id: expected.actorIdentityId,
  trace_id: null,
  occurred_at: '2026-10-15T00:00:01Z'
};
const outbox = {
  published_at: '2026-10-15T00:00:00Z',
  event_snapshot: {
    id: audit.source_event_id,
    source: audit.source,
    type: audit.source_type,
    time: audit.occurred_at,
    data: { assetId: expected.resourceId, actorIdentityId: expected.actorIdentityId }
  }
};

test('links a committed Stage 4 fact to one published source event and one persisted Audit row', () => {
  assert.equal(validateLinkedFact(expected, [audit], [outbox]), true);
});

test('rejects an uncommitted or differently mapped fact', () => {
  assert.throws(() => validateLinkedFact(expected, [audit], [{ ...outbox, published_at: null }]));
  assert.throws(() => validateLinkedFact(expected, [{ ...audit, action: 'BRAND_PROFILE_SAVED' }], [outbox]));
  assert.throws(() => validateLinkedFact(expected, [audit, audit], [outbox]));
  assert.throws(() => validateLinkedFact({ ...expected, source: 'toString' }, [audit], [outbox]));
  assert.throws(() => validateLinkedFact(expected, [{ ...audit, occurred_at: '2026-10-14T23:00:00Z' }], [outbox]));
  assert.throws(() => validateLinkedFact(expected, [audit], [{
    ...outbox, event_snapshot: { ...outbox.event_snapshot, time: '2026-10-14T23:00:00Z' }
  }]));
});

test('rejects credential fields or known secret values in persisted facts', () => {
  assert.throws(() => validateLinkedFact(expected, [{ ...audit, metadata: { password: 'stored' } }], [outbox]));
  assert.throws(() => validateLinkedFact(expected, [audit], [{
    ...outbox, event_snapshot: { ...outbox.event_snapshot, data: { password: 'stored' } }
  }]));
  assert.throws(() => validateLinkedFact(expected, [audit], [{
    ...outbox, event_snapshot: { ...outbox.event_snapshot, data: { note: 'known-private-value' } }
  }], ['known-private-value']));
});

test('distinguishes two member events with the same actor and membership by the changed unit', () => {
  const unitId = '0199d1b0-1111-7000-8000-000000000004';
  const association = { ...expected, action: 'MEMBERSHIP_ORGANIZATION_UNIT_ADDED',
    relatedResourceId: unitId };
  const linkedAudit = { ...audit, action: association.action, metadata: { unitId } };
  const linkedOutbox = { ...outbox,
    event_snapshot: { ...outbox.event_snapshot, data: { unitId } } };
  assert.equal(validateLinkedFact(association, [linkedAudit], [linkedOutbox]), true);
  assert.throws(() => validateLinkedFact(association,
    [{ ...linkedAudit, metadata: { unitId: expected.resourceId } }], [linkedOutbox]),
  /AUDIT_RELATED_ID_MISMATCH/);
  assert.throws(() => validateLinkedFact(association, [linkedAudit], [{ ...linkedOutbox,
    event_snapshot: { ...linkedOutbox.event_snapshot, data: { unitId: expected.resourceId } }
  }]), /EVENT_RELATED_ID_MISMATCH/);
});
