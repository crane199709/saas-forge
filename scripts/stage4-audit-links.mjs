import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const SOURCES = Object.freeze({
  iam: { source: 'urn:saas.forge:iam-service', database: 'iam_db', table: 'iam_outbox_events' },
  'tenant-access': {
    source: 'urn:saas.forge:tenant-access-service', database: 'tenant_access_db',
    table: 'tenant_access_outbox_events'
  },
  project: { source: 'urn:saas.forge:project-service', database: 'project_db', table: 'project_outbox_events' }
});
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const ACTION = /^[A-Z]+(?:_[A-Z]+)+$/;
const SHA = value => createHash('sha256').update(value).digest('hex');

function privateJson(path) {
  const details = statSync(path);
  assert.ok(details.isFile() && details.size < 524288 && (details.mode & 0o077) === 0,
    'INPUT_MUST_BE_PRIVATE');
  const bytes = readFileSync(path);
  return { value: JSON.parse(bytes), sha256: SHA(bytes) };
}

function validateObservation(value) {
  assert.ok(value && typeof value === 'object' && !Array.isArray(value));
  const keys = ['action', 'actorIdentityId', 'finishedAt', 'label', 'resourceId', 'source',
    'startedAt'];
  if (Object.hasOwn(value, 'relatedResourceId')) keys.push('relatedResourceId');
  assert.deepEqual(Object.keys(value).sort(), keys.sort());
  assert.match(value.label, /^[a-z][a-z0-9-]{2,80}$/);
  assert.ok(Object.hasOwn(SOURCES, value.source));
  assert.match(value.resourceId, UUID);
  assert.ok(value.actorIdentityId === null || UUID.test(value.actorIdentityId));
  assert.match(value.action, ACTION);
  if (value.relatedResourceId !== undefined) {
    assert.match(value.relatedResourceId, UUID);
    assert.ok(['MEMBERSHIP_ORGANIZATION_UNIT_ADDED', 'MEMBERSHIP_ORGANIZATION_UNIT_REMOVED',
      'MEMBERSHIP_ROLE_ASSIGNED', 'MEMBERSHIP_ROLE_REVOKED'].includes(value.action));
  }
  assert.ok(Number.isFinite(Date.parse(value.startedAt)) &&
    Number.isFinite(Date.parse(value.finishedAt)) &&
    Date.parse(value.startedAt) <= Date.parse(value.finishedAt), 'OPERATION_WINDOW_INVALID');
}

function rejectSensitiveMaterial(value, secrets) {
  const forbidden = new Set(['password', 'secret', 'token', 'email', 'invitationlink',
    'challenge', 'objectkey', 'privatekey']);
  function visit(item) {
    if (Array.isArray(item)) return item.forEach(visit);
    if (item && typeof item === 'object') {
      for (const [key, nested] of Object.entries(item)) {
        assert.ok(!forbidden.has(key.toLowerCase()), 'SENSITIVE_FIELD_PRESENT');
        visit(nested);
      }
    }
  }
  visit(value);
  const serialized = JSON.stringify(value);
  for (const secret of secrets) assert.ok(!serialized.includes(secret), 'KNOWN_SECRET_PRESENT');
}

/** Public Stage 4 evidence seam: one successful source Outbox event must map to one persistent Audit row. */
export function validateLinkedFact(expected, auditRows, outboxRows, secrets = []) {
  validateObservation(expected);
  assert.equal(auditRows.length, 1, 'AUDIT_ROW_COUNT');
  assert.equal(outboxRows.length, 1, 'OUTBOX_ROW_COUNT');
  const audit = auditRows[0], outbox = outboxRows[0];
  const event = outbox.event_snapshot;
  assert.match(audit.source_event_id, UUID);
  assert.equal(audit.source, SOURCES[expected.source].source);
  assert.equal(audit.action, expected.action);
  assert.equal(audit.result, 'SUCCESS');
  assert.equal(audit.resource_id, expected.resourceId);
  assert.equal(audit.actor_identity_id, expected.actorIdentityId);
  if (expected.relatedResourceId !== undefined) {
    const key = expected.action.startsWith('MEMBERSHIP_ROLE_') ? 'roleId' : 'unitId';
    assert.equal(audit.metadata?.[key], expected.relatedResourceId, 'AUDIT_RELATED_ID_MISMATCH');
    assert.equal(event.data?.[key], expected.relatedResourceId, 'EVENT_RELATED_ID_MISMATCH');
  }
  const occurredAt = Date.parse(audit.occurred_at);
  assert.ok(Number.isFinite(occurredAt) &&
    occurredAt >= Date.parse(expected.startedAt) - 2000 &&
    occurredAt <= Date.parse(expected.finishedAt) + 2000, 'AUDIT_OUTSIDE_OPERATION_WINDOW');
  assert.ok(outbox.published_at && Number.isFinite(Date.parse(outbox.published_at)));
  assert.equal(event.id, audit.source_event_id);
  assert.equal(event.source, audit.source);
  assert.equal(event.type, audit.source_type);
  assert.equal(event.traceId ?? null, audit.trace_id);
  assert.equal(Date.parse(event.time), occurredAt);
  rejectSensitiveMaterial(audit.metadata ?? {}, secrets);
  rejectSensitiveMaterial(event, secrets);
  return true;
}

function docker(...args) {
  return execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 }).trim();
}

function freshPostgres(handoff) {
  assert.equal(handoff.status, 'ready');
  assert.equal(handoff.isolation.kind, 'fresh-compose');
  const project = `sf-acceptance-${handoff.runId}`;
  assert.equal(handoff.isolation.project, project);
  assert.ok(Date.now() < Date.parse(handoff.expiresAt));
  const ids = docker('ps', '-aq', '--filter', `label=com.docker.compose.project=${project}`,
    '--filter', 'label=com.docker.compose.service=postgres').split(/\s+/).filter(Boolean);
  assert.equal(ids.length, 1);
  const instance = JSON.parse(docker('inspect', ids[0]))[0];
  assert.ok(instance.State.Running && Date.parse(instance.Created) >= Date.parse(handoff.startedAt));
  assert.ok(instance.Mounts.some(mount => mount.Type === 'volume' &&
    mount.Name === `${project}_postgres-data`));
  return ids[0];
}

function query(postgres, database, sql) {
  const output = docker('exec', postgres, 'sh', '-c',
    'exec psql --username "$POSTGRES_USER" --dbname "$1" -v ON_ERROR_STOP=1 -At -c "$2"',
    'psql', database, `BEGIN READ ONLY; ${sql}; COMMIT`);
  const lines = output.split('\n').filter(line => line.startsWith('['));
  assert.equal(lines.length, 1, 'DATABASE_RESULT_INVALID');
  return JSON.parse(lines[0]);
}

function rows(postgres, expected) {
  const source = SOURCES[expected.source];
  const from = new Date(Date.parse(expected.startedAt) - 2000).toISOString();
  const to = new Date(Date.parse(expected.finishedAt) + 2000).toISOString();
  const actor = expected.actorIdentityId === null ? 'actor_identity_id IS NULL'
    : `actor_identity_id='${expected.actorIdentityId}'`;
  const relatedKey = expected.action.startsWith('MEMBERSHIP_ROLE_') ? 'roleId' : 'unitId';
  const related = expected.relatedResourceId === undefined ? ''
    : `AND metadata->>'${relatedKey}'='${expected.relatedResourceId}'`;
  // Only validated UUID, normalized timestamps, strict action and fixed source enter SQL.
  const audit = query(postgres, 'audit_db', `SELECT COALESCE(json_agg(a),'[]'::json) FROM (
    SELECT source_event_id,source,source_type,action,result,resource_id,actor_identity_id,
      trace_id,metadata,occurred_at
    FROM audit_records WHERE source='${source.source}' AND action='${expected.action}'
      AND resource_id='${expected.resourceId}' AND ${actor} ${related}
      AND occurred_at BETWEEN '${from}' AND '${to}') a`);
  assert.ok(audit.length <= 1, 'AUDIT_OPERATION_AMBIGUOUS');
  if (audit.length === 0) return { audit, outbox: [] };
  assert.match(audit[0].source_event_id, UUID);
  const outbox = query(postgres, source.database, `SELECT COALESCE(json_agg(o),'[]'::json) FROM (
    SELECT event_snapshot,published_at FROM ${source.table}
    WHERE event_id='${audit[0].source_event_id}') o`);
  return { audit, outbox };
}

async function run(handoffPath, observationsPath, outputPath) {
  const handoff = privateJson(handoffPath);
  const input = privateJson(observationsPath);
  assert.equal(input.value.runId, handoff.value.runId);
  assert.ok(Array.isArray(input.value.operations) && input.value.operations.length > 0 &&
    input.value.operations.length <= 100);
  input.value.operations.forEach(validateObservation);
  for (const operation of input.value.operations) {
    assert.ok(Date.parse(operation.startedAt) >= Date.parse(handoff.value.readyAt) &&
      Date.parse(operation.finishedAt) <= Date.parse(handoff.value.expiresAt),
    'OPERATION_OUTSIDE_HANDOFF_WINDOW');
  }
  assert.ok(Array.isArray(input.value.sensitiveFiles) && input.value.sensitiveFiles.length > 0 &&
    input.value.sensitiveFiles.length <= 100);
  const secrets = input.value.sensitiveFiles.map(path => {
    const details = statSync(path);
    assert.ok(details.isFile() && details.size <= 4096 && details.uid === process.getuid() &&
      details.gid === process.getgid() && (details.mode & 0o137) === 0,
      'SENSITIVE_FILE_NOT_PRIVATE');
    const value = readFileSync(path, 'utf8').trim();
    assert.ok(value.length >= 12, 'SENSITIVE_VALUE_TOO_SHORT');
    return value;
  });
  assert.equal(new Set(input.value.operations.map(operation => operation.label)).size,
    input.value.operations.length);
  const postgres = freshPostgres(handoff.value);
  const report = {
    schemaVersion: 1, kind: 'stage4-audit-links', scope: 'linked-observations-only',
    runId: handoff.value.runId, handoffSha256: handoff.sha256,
    observationsSha256: input.sha256, startedAt: new Date().toISOString(),
    status: 'failed', checks: []
  };
  const linkedEvents = new Set();
  try {
    for (const operation of input.value.operations) {
      const check = { name: operation.label, action: operation.action,
        source: operation.source, status: 'failed' };
      report.checks.push(check);
      let result;
      for (let attempt = 0; attempt < 60; attempt += 1) {
        result = rows(postgres, operation);
        if (result.audit.length === 1 && result.outbox.length === 1 &&
          result.outbox[0].published_at) break;
        if (attempt < 59) await delay(1000);
      }
      validateLinkedFact(operation, result.audit, result.outbox, secrets);
      assert.ok(!linkedEvents.has(result.audit[0].source_event_id), 'DUPLICATE_OBSERVATION');
      linkedEvents.add(result.audit[0].source_event_id);
      check.eventSha256 = SHA(`${handoff.value.runId}:${result.audit[0].source_event_id}`);
      check.status = 'passed';
    }
    report.status = 'passed';
    report.sensitiveValueCount = secrets.length;
  } catch {
    report.reason = 'AUDIT_LINK_REJECTED';
    process.exitCode = 1;
  } finally {
    report.finishedAt = new Date().toISOString();
    writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600, flag: 'wx' });
    console.log(JSON.stringify({ status: report.status, checks: report.checks.length,
      scope: report.scope }));
  }
}

if (process.argv[1] && resolve(process.argv[1]) === new URL(import.meta.url).pathname) {
  try {
    if (process.argv.length !== 5) throw new Error('USAGE');
    await run(...process.argv.slice(2));
  } catch {
    console.error('STAGE4_AUDIT_LINKS_FAILED: verify private inputs and this Fresh PostgreSQL project');
    process.exitCode = 1;
  }
}
