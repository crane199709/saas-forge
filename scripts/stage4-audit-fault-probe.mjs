import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const RUN_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SHA = /^[0-9a-f]{64}$/;
const digest = value => createHash('sha256').update(value).digest('hex');
const requireUuid = value => { assert.match(value, UUID, 'UUID_V7_REQUIRED'); return value; };

function uuidV7() {
  const bytes = randomBytes(16);
  bytes.writeUIntBE(Date.now(), 0, 6);
  bytes[6] = (bytes[6] & 15) | 0x70;
  bytes[8] = (bytes[8] & 63) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function privateFile(path, limit = 524288) {
  const info = statSync(path);
  assert.ok(info.isFile() && info.size > 0 && info.size <= limit &&
    info.uid === process.getuid() && info.gid === process.getgid() &&
    (info.mode & 0o077) === 0, 'PRIVATE_FILE_REQUIRED');
  return readFileSync(path);
}

function token(path, tenantId) {
  const value = privateFile(path, 8192).toString('utf8').trim();
  assert.match(value, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/, 'TOKEN_INVALID');
  const claims = JSON.parse(Buffer.from(value.split('.')[1], 'base64url').toString('utf8'));
  assert.equal(claims.tenantId, tenantId, 'TOKEN_TENANT_MISMATCH');
  assert.ok(Number.isInteger(claims.exp) && claims.exp > Date.now() / 1000 + 180,
    'TOKEN_EXPIRES_TOO_SOON');
  return value;
}

export function validateRetryEvidence(outbox, audit, resourceId) {
  requireUuid(resourceId);
  assert.equal(outbox.type, 'com.saas.forge.example.project.updated.v1');
  assert.equal(outbox.resourceId, resourceId);
  requireUuid(outbox.eventId);
  assert.ok(Number.isInteger(outbox.attemptCount) && outbox.attemptCount >= 2,
    'SOURCE_OUTBOX_NOT_RETRIED');
  assert.ok(Number.isFinite(Date.parse(outbox.publishedAt)), 'SOURCE_OUTBOX_NOT_PUBLISHED');
  assert.equal(outbox.lastFailure, null, 'SOURCE_OUTBOX_FAILURE_NOT_CLEARED');
  assert.match(outbox.snapshotSha256, SHA);
  assert.equal(audit.recordCount, 1, 'AUDIT_RECORD_DUPLICATED');
  assert.equal(audit.consumedCount, 1, 'AUDIT_CONSUMPTION_DUPLICATED');
  assert.equal(audit.sourceEventId, outbox.eventId, 'AUDIT_SOURCE_EVENT_MISMATCH');
  assert.equal(audit.action, 'PROJECT_UPDATED');
  assert.equal(audit.result, 'SUCCESS');
  return true;
}

export function validateRollbackEvidence(before, after, writeResults, original, current) {
  assert.equal(after.outboxCount, before.outboxCount, 'FAILED_OUTBOX_COMMITTED');
  assert.equal(after.auditCount, before.auditCount, 'FAILED_AUDIT_FACT_RECORDED');
  assert.equal(writeResults, 0, 'FAILED_IDEMPOTENCY_RESULT_COMMITTED');
  assert.equal(current.id, original.id);
  assert.equal(current.version, original.version, 'FAILED_PROJECT_VERSION_COMMITTED');
  assert.equal(current.name, original.name, 'FAILED_PROJECT_NAME_COMMITTED');
  return true;
}

function command(name, args, input = '', timeout = 30000) {
  return new Promise((done, fail) => {
    const child = spawn(name, args, { stdio: ['pipe', 'pipe', 'pipe'] });
    const output = [];
    const timer = setTimeout(() => child.kill('SIGKILL'), timeout);
    child.stdout.on('data', part => output.push(part));
    child.stderr.resume();
    child.on('error', () => { clearTimeout(timer); fail(new Error('SUBPROCESS_FAILED')); });
    child.on('close', code => {
      clearTimeout(timer);
      if (code !== 0) fail(new Error('SUBPROCESS_FAILED'));
      else done(Buffer.concat(output).toString('utf8').trim());
    });
    child.stdin.end(input);
  });
}

async function postgresContainer(handoff) {
  const project = `sf-acceptance-${handoff.runId}`;
  const ids = (await command('docker', ['ps', '-aq', '--filter',
    `label=com.docker.compose.project=${project}`, '--filter',
    'label=com.docker.compose.service=postgres'])).split(/\s+/).filter(Boolean);
  assert.equal(ids.length, 1, 'FRESH_POSTGRES_COUNT');
  const [instance] = JSON.parse(await command('docker', ['inspect', ids[0]]));
  assert.ok(instance.State.Running && Date.parse(instance.Created) >= Date.parse(handoff.startedAt) &&
    instance.Mounts.some(mount => mount.Type === 'volume' &&
      mount.Name === `${project}_postgres-data`), 'FRESH_POSTGRES_IDENTITY');
  return ids[0];
}

function database(container) {
  const psql = (name, sql) => {
    assert.ok(['project_db', 'audit_db'].includes(name));
    return command('docker', ['exec', '-i', container, 'sh', '-c',
      'exec psql -qAt -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$1"',
      'psql', name], `${sql}\n`);
  };
  return {
    execute: (name, sql) => psql(name, sql),
    async read(name, sql) {
      const output = await psql(name, `BEGIN READ ONLY; ${sql}; COMMIT;`);
      const row = output.split('\n').find(line => line.startsWith('{'));
      assert.ok(row, 'DATABASE_RESULT_INVALID');
      return JSON.parse(row);
    }
  };
}

async function withFault(db, projectId, kind, task) {
  requireUuid(projectId);
  assert.ok(['retry', 'rollback'].includes(kind));
  const suffix = randomBytes(6).toString('hex');
  const stem = `stage4_audit_${kind}_${suffix}`;
  const condition = `NEW.event_snapshot->>'type' = 'com.saas.forge.example.project.updated.v1'
    AND NEW.event_snapshot->'data'->>'resourceId' = '${projectId}'`;
  const fails = kind === 'retry'
    ? `OLD.published_at IS NULL AND NEW.published_at IS NOT NULL AND ${condition}
      AND NEW.attempt_count = 1`
    : condition;
  const install = `CREATE FUNCTION ${stem}_fn() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN IF ${fails} THEN RAISE EXCEPTION 'stage4 controlled outbox failure';
    END IF; RETURN NEW; END $$;
    CREATE TRIGGER ${stem}_trg BEFORE ${kind === 'retry' ? 'UPDATE OF published_at' : 'INSERT'}
    ON project_outbox_events FOR EACH ROW EXECUTE FUNCTION ${stem}_fn();`;
  const cleanup = `DROP TRIGGER IF EXISTS ${stem}_trg ON project_outbox_events;
    DROP FUNCTION IF EXISTS ${stem}_fn();`;
  let signal = null;
  const interrupt = name => { signal = name; };
  const onInt = () => interrupt('SIGINT');
  const onTerm = () => interrupt('SIGTERM');
  process.on('SIGINT', onInt);
  process.on('SIGTERM', onTerm);
  try {
    await db.execute('project_db', install);
    if (signal) throw new Error('INTERRUPTED');
    const result = await task();
    if (signal) throw new Error('INTERRUPTED');
    return result;
  } finally {
    try { await db.execute('project_db', cleanup); }
    finally {
      process.off('SIGINT', onInt);
      process.off('SIGTERM', onTerm);
    }
  }
}

async function eventRows(db, projectId, since) {
  requireUuid(projectId);
  assert.ok(Number.isFinite(Date.parse(since)));
  const rows = await db.read('project_db', `SELECT json_build_object('rows',
    COALESCE(json_agg(json_build_object('eventId',event_id,'type',event_snapshot->>'type',
      'resourceId',event_snapshot->'data'->>'resourceId','attemptCount',attempt_count,
      'publishedAt',published_at,'lastFailure',last_failure,
      'snapshot',event_snapshot) ORDER BY occurred_at),'[]'::json))
    FROM project_outbox_events WHERE event_snapshot->>'type'=
      'com.saas.forge.example.project.updated.v1'
      AND event_snapshot->'data'->>'resourceId'='${projectId}'
      AND occurred_at >= '${since}'::timestamptz`);
  return rows.rows;
}

async function auditFor(db, eventId) {
  requireUuid(eventId);
  return db.read('audit_db', `SELECT json_build_object(
    'recordCount',(SELECT count(*) FROM audit_records WHERE source_event_id='${eventId}'
      AND source='urn:saas.forge:project-service'),
    'consumedCount',(SELECT count(*) FROM audit_consumed_events WHERE event_id='${eventId}'
      AND source='urn:saas.forge:project-service'),
    'sourceEventId',(SELECT source_event_id FROM audit_records WHERE source_event_id='${eventId}'
      AND source='urn:saas.forge:project-service' LIMIT 1),
    'action',(SELECT action FROM audit_records WHERE source_event_id='${eventId}'
      AND source='urn:saas.forge:project-service' LIMIT 1),
    'result',(SELECT result FROM audit_records WHERE source_event_id='${eventId}'
      AND source='urn:saas.forge:project-service' LIMIT 1))`);
}

async function counts(db, projectId) {
  requireUuid(projectId);
  const outbox = await db.read('project_db', `SELECT json_build_object('count',count(*))
    FROM project_outbox_events WHERE event_snapshot->>'type'=
      'com.saas.forge.example.project.updated.v1'
      AND event_snapshot->'data'->>'resourceId'='${projectId}'`);
  const audit = await db.read('audit_db', `SELECT json_build_object('count',count(*))
    FROM audit_records WHERE source='urn:saas.forge:project-service'
      AND action='PROJECT_UPDATED' AND resource_id='${projectId}'`);
  return { outboxCount: Number(outbox.count), auditCount: Number(audit.count) };
}

async function awaitRetry(db, projectId, since) {
  for (let n = 0; n < 60; n += 1) {
    const rows = await eventRows(db, projectId, since);
    if (rows.length === 1 && rows[0].publishedAt && rows[0].attemptCount >= 2) {
      const row = rows[0];
      const audit = await auditFor(db, row.eventId);
      if (audit.recordCount === 1 && audit.consumedCount === 1) {
        const outbox = { ...row, snapshotSha256: digest(JSON.stringify(row.snapshot)) };
        delete outbox.snapshot;
        validateRetryEvidence(outbox, audit, projectId);
        return outbox;
      }
    }
    await delay(1000);
  }
  throw new Error('SOURCE_RETRY_OR_AUDIT_TIMEOUT');
}

async function loadClient(directory) {
  assert.equal(resolve(directory), directory, 'CLIENT_DIRECTORY_ABSOLUTE_REQUIRED');
  const metadata = JSON.parse(readFileSync(join(directory, 'package.json')));
  const source = JSON.parse(readFileSync(join(directory, 'contract-source.json')));
  assert.equal(metadata.name, '@crane199709/saas-forge-api-client');
  assert.equal(metadata.version, '0.6.0');
  assert.equal(source.package, metadata.name);
  assert.equal(source.version, metadata.version);
  assert.equal(source.dirty, false);
  assert.ok(typeof source.commit === 'string' && /^[0-9a-f]{40}$/.test(source.commit));
  const client = await import(pathToFileURL(join(directory, 'dist/index.js')).href);
  return { client, source };
}

async function run(handoffPath, scenarioPath, outputPath) {
  const handoffBytes = privateFile(handoffPath);
  const scenarioBytes = privateFile(scenarioPath);
  const handoff = JSON.parse(handoffBytes), scenario = JSON.parse(scenarioBytes);
  assert.equal(handoff.schemaVersion, 1);
  assert.equal(handoff.status, 'ready');
  assert.match(handoff.runId, RUN_ID, 'RUN_ID_INVALID');
  assert.equal(handoff.isolation?.kind, 'fresh-compose');
  assert.equal(handoff.isolation.project, `sf-acceptance-${handoff.runId}`);
  assert.ok(handoff.requiredServices?.includes('project-service') &&
    handoff.requiredServices?.includes('audit-service'));
  assert.ok(Date.now() >= Date.parse(handoff.readyAt) && Date.now() < Date.parse(handoff.expiresAt));
  assert.equal(new URL(handoff.apiOrigin).protocol, 'https:');
  assert.equal(new URL(handoff.apiOrigin).origin, handoff.apiOrigin);
  assert.equal(scenario.runId, handoff.runId);
  assert.deepEqual(Object.keys(scenario).sort(),
    ['runId', 'tenantId', 'tokenFile', 'clientPackageDirectory'].sort());
  requireUuid(scenario.tenantId);
  const accessToken = token(scenario.tokenFile, scenario.tenantId);
  const { client, source } = await loadClient(scenario.clientPackageDirectory);
  const db = database(await postgresContainer(handoff));
  const api = new client.Project.DefaultApi(new client.Configuration({
    basePath: handoff.apiOrigin, accessToken
  }));
  const report = { schemaVersion: 1, kind: 'stage4-audit-fault-probe',
    scope: 'project-outbox-retry-and-transaction-rollback', runId: handoff.runId,
    handoffSha256: digest(handoffBytes), scenarioSha256: digest(scenarioBytes),
    client: { name: source.package, version: source.version, sourceCommit: source.commit },
    startedAt: new Date().toISOString(), status: 'failed', checks: [] };
  try {
    const name = `stage4-audit-${handoff.runId.slice(0, 8)}-${randomBytes(3).toString('hex')}`;
    const created = await api.createProject({ idempotencyKey: uuidV7(),
      createProject: { name } });
    requireUuid(created.id);
    assert.equal(created.version, 1);
    const retrySince = new Date().toISOString();
    const first = await withFault(db, created.id, 'retry', async () => {
      const updated = await api.updateProject({ projectId: created.id,
        idempotencyKey: uuidV7(), ifMatch: `"${created.version}"`,
        createProject: { name: `${name}-retry` } });
      assert.equal(updated.version, created.version + 1);
      const outbox = await awaitRetry(db, created.id, retrySince);
      report.checks.push({ name: 'outbox-retry-and-audit-idempotency', status: 'passed',
        resourceSha256: digest(`${handoff.runId}:${created.id}`),
        eventSha256: digest(`${handoff.runId}:${outbox.eventId}`),
        snapshotSha256: outbox.snapshotSha256, attempts: outbox.attemptCount });
      return updated;
    });
    const original = await api.getProject({ projectId: created.id });
    assert.equal(original.version, first.version);
    const before = await counts(db, created.id);
    const failedKey = uuidV7();
    const failedName = `${name}-rollback`;
    await withFault(db, created.id, 'rollback', async () => {
      let failedStatus = null;
      try {
        await api.updateProject({ projectId: created.id,
          idempotencyKey: failedKey, ifMatch: `"${original.version}"`,
          createProject: { name: failedName } });
      } catch (error) { failedStatus = error?.response?.status ?? null; }
      assert.equal(failedStatus, 503, 'FAILED_TRANSACTION_HTTP_STATUS');
      const current = await api.getProject({ projectId: created.id });
      const after = await counts(db, created.id);
      const result = await db.read('project_db', `SELECT json_build_object('count',count(*))
        FROM project_write_results WHERE idempotency_key='${failedKey}'`);
      validateRollbackEvidence(before, after, Number(result.count), original, current);
    });
    const recovered = await api.updateProject({ projectId: created.id,
      idempotencyKey: failedKey, ifMatch: `"${original.version}"`,
      createProject: { name: failedName } });
    assert.equal(recovered.version, original.version + 1, 'SAME_KEY_RECOVERY_FAILED');
    const recoveryRows = await eventRows(db, created.id, report.startedAt);
    assert.equal(recoveryRows.filter(row => row.snapshot?.data?.version === recovered.version).length,
      1, 'RECOVERED_FACT_COUNT');
    report.checks.push({ name: 'failed-transactions-have-no-success-fact', status: 'passed',
      recoveryVersion: recovered.version });
    report.status = 'passed';
  } catch (error) {
    report.reason = /^[A-Z0-9_]{3,90}$/.test(error.message)
      ? error.message : 'AUDIT_FAULT_PROBE_REJECTED';
    process.exitCode = 1;
  } finally {
    report.finishedAt = new Date().toISOString();
    writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600, flag: 'wx' });
    console.log(JSON.stringify({ kind: report.kind, status: report.status,
      checks: report.checks.length }));
  }
}

if (process.argv[1] && resolve(process.argv[1]) === new URL(import.meta.url).pathname) {
  try {
    if (process.argv.length !== 5) throw new Error('USAGE');
    await run(...process.argv.slice(2));
  } catch (error) {
    const code = /^[A-Z0-9_]{3,90}$/.test(error?.message ?? '')
      ? error.message : 'STAGE4_AUDIT_FAULT_PROBE_FAILED';
    console.error(code);
    process.exitCode = 1;
  }
}
