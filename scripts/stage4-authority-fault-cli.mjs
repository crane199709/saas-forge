#!/usr/bin/env node
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { runClosure, runClosureRecovery, runInvitationCompensation,
  validateFaultScenario } from './stage4-authority-fault-driver.mjs';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const hash = value => createHash('sha256').update(value).digest('hex');
const identifier = value => { assert.match(value, UUID); return value; };
let interrupted = false;
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { interrupted = true; });

function privateFile(path) {
  const details = statSync(path);
  assert.ok(details.isFile() && details.size > 0 && details.size < 524288 &&
    details.uid === process.getuid() && (details.mode & 0o077) === 0, 'PRIVATE_FILE_REQUIRED');
  return readFileSync(path, 'utf8');
}

function privateToken(path) {
  const token = privateFile(path).trim();
  assert.match(token, /^[A-Za-z0-9._~-]+$/, 'PRIVATE_TOKEN_INVALID');
  return token;
}

function args(argv) {
  const options = Object.fromEntries(argv.reduce((pairs, value, index) => {
    if (index % 2 === 0) {
      assert.ok(value.startsWith('--'));
      pairs.push([value.slice(2), argv[index + 1]]);
    }
    return pairs;
  }, []));
  assert.equal(argv.length, 8, 'USAGE: --mode invitation|closure|closure-recovery --run HANDOFF --scenario PRIVATE_JSON --output NEW_DIRECTORY');
  assert.deepEqual(Object.keys(options).sort(), ['mode', 'output', 'run', 'scenario']);
  return options;
}

async function command(name, parameters, timeout = 30000) {
  return await new Promise((done, fail) => {
    const child = spawn(name, parameters, { stdio: ['ignore', 'pipe', 'pipe'] });
    const chunks = [];
    const timer = setTimeout(() => child.kill('SIGKILL'), timeout);
    child.stdout.on('data', chunk => chunks.push(chunk));
    child.stderr.resume();
    child.on('error', error => { clearTimeout(timer); fail(error); });
    child.on('close', code => {
      clearTimeout(timer);
      if (code === 0) done(Buffer.concat(chunks).toString('utf8').trim());
      else fail(new Error(`${name.toUpperCase()}_FAILED_${code}`));
    });
  });
}

async function onlyContainer(project, service) {
  const found = (await command('docker', ['ps', '-aq', '--filter',
    `label=com.docker.compose.project=${project}`, '--filter',
    `label=com.docker.compose.service=${service}`])).split(/\s+/).filter(Boolean);
  assert.equal(found.length, 1, `FRESH_${service.toUpperCase()}_COUNT`);
  const [details] = JSON.parse(await command('docker', ['inspect', found[0]]));
  assert.ok(details.State.Running &&
    details.Config.Labels['com.docker.compose.project'] === project &&
    details.Config.Labels['com.docker.compose.service'] === service, 'FRESH_CONTAINER_IDENTITY');
  if (service === 'postgres') assert.ok(details.Mounts.some(mount => mount.Type === 'volume' &&
    mount.Name === `${project}_postgres-data`), 'FRESH_POSTGRES_VOLUME');
  return details.Id;
}

async function postgres(container, db, query) {
  assert.ok(['tenant_access_db', 'entitlement_db', 'iam_db'].includes(db));
  const output = await command('docker', ['exec', container, 'sh', '-c',
    'exec psql --username "$POSTGRES_USER" --dbname "$1" --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 --command "$2"',
    'stage4', db, query]);
  return output;
}

async function row(container, db, select) {
  const output = await postgres(container, db, `SELECT row_to_json(q) FROM (${select}) q`);
  return output ? JSON.parse(output) : null;
}

async function poll(label, seconds, read, ready) {
  for (let attempt = 0; attempt < seconds; attempt++) {
    if (interrupted) throw new Error('INTERRUPTED');
    const value = await read();
    if (ready(value)) return value;
    await delay(1000);
  }
  throw new Error(`${label}_TIMEOUT`);
}

async function api(origin, token, method, path, key = null) {
  const headers = { Authorization: `Bearer ${token}` };
  if (key) headers['Idempotency-Key'] = key;
  const response = await fetch(new URL(path, origin), { method, headers,
    signal: AbortSignal.timeout(15000) });
  let body = {};
  try { body = await response.json(); } catch { /* 204 or non-JSON */ }
  return { status: response.status, code: body.code ?? null, body };
}

function writePrivate(path, value) {
  writeFileSync(path, JSON.stringify(value, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
}

async function adapter(handoff, target, output) {
  const project = handoff.isolation.project;
  const pg = await onlyContainer(project, 'postgres');
  for (const frozen of handoff.backend.images) {
    assert.equal(await onlyContainer(project, frozen.service), frozen.containerId,
      `FROZEN_${frozen.service}_CONTAINER_DRIFT`);
  }
  const faultName = `stage4_accept_${hash(target.invitationId ?? target.tenantId).slice(0, 16)}`;
  const iamFaultName = `stage4_iam_${hash(target.tenantId).slice(0, 16)}`;
  let installed = false;
  let iamFaultInstalled = false;
  const platformToken = target.platformTokenFile ? privateToken(target.platformTokenFile) : null;
  const oldTenantToken = target.oldTenantTokenFile ? privateToken(target.oldTenantTokenFile) : null;
  const tenantId = identifier(target.tenantId);
  const invitationId = target.invitationId ? identifier(target.invitationId) : null;

  async function workflow() {
    return await row(pg, 'tenant_access_db', `SELECT workflow_status AS state,
      revocation_request_id AS "requestId", fence_established AS "fenceEstablished"
      FROM tenant_lifecycle_workflows WHERE tenant_id='${tenantId}' AND lifecycle_action='CLOSE'
      ORDER BY created_at DESC LIMIT 1`);
  }
  async function invitation() {
    const local = await row(pg, 'tenant_access_db', `SELECT a.state,
      a.consume_operation_id AS "consumeId", a.release_operation_id AS "releaseId",
      i.invitation_status AS "invitationStatus",
      (SELECT count(*)::integer FROM memberships m WHERE m.tenant_id='${tenantId}'
        AND m.identity_id=a.identity_id) AS "membershipCount",
      (SELECT count(*)::integer FROM tenant_access_outbox_events e
        WHERE e.tenant_id='${tenantId}' AND e.event_snapshot->>'type'='com.saas.forge.invitation.accepted.v1'
          AND e.event_snapshot->'data'->>'invitationId'='${invitationId}') AS "acceptedFacts"
      FROM invitation_acceptance_attempts a JOIN invitations i ON i.id=a.invitation_id
      WHERE a.invitation_id='${invitationId}'`);
    if (!local) return null;
    const quota = await row(pg, 'entitlement_db', `SELECT
      (SELECT outcome FROM quota_operations WHERE operation_id='${local.consumeId}') AS "consumeOutcome",
      (SELECT outcome FROM quota_operations WHERE operation_id='${local.releaseId}') AS "releaseOutcome",
      COALESCE((SELECT used FROM quota_usages u JOIN quota_definitions d
        ON d.id=u.quota_definition_id WHERE u.tenant_id='${tenantId}' AND d.code='max_users'),0) AS usage`);
    return { ...local, ...quota };
  }
  const result = {
    async readQuotaUsage() {
      const value = await row(pg, 'entitlement_db', `SELECT COALESCE((SELECT used FROM quota_usages u
        JOIN quota_definitions d ON d.id=u.quota_definition_id WHERE u.tenant_id='${tenantId}'
        AND d.code='max_users'),0) AS usage`);
      return value.usage;
    },
    async installInvitationFault() {
      const existing = await row(pg, 'tenant_access_db', `SELECT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname='${faultName}') AS present`);
      assert.equal(existing.present, false, 'FAULT_ALREADY_EXISTS');
      await postgres(pg, 'tenant_access_db', `BEGIN;
        CREATE FUNCTION ${faultName}() RETURNS trigger
        LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'STAGE4_SCOPED_INVITATION_FAULT'; END $$;
        CREATE TRIGGER ${faultName} BEFORE INSERT ON tenant_access_outbox_events
        FOR EACH ROW WHEN (NEW.tenant_id='${tenantId}' AND
          NEW.event_snapshot->>'type'='com.saas.forge.invitation.accepted.v1' AND
          NEW.event_snapshot->'data'->>'invitationId'='${invitationId}')
        EXECUTE FUNCTION ${faultName}(); COMMIT;`);
      installed = true;
      writePrivate(resolve(output, 'invitation-fault-ready.json'),
        { runId: handoff.runId, phase: 'accept-in-Chrome-now', tenantId, invitationId });
    },
    async removeInvitationFault() {
      if (!installed) return;
      await postgres(pg, 'tenant_access_db', `DROP TRIGGER IF EXISTS ${faultName}
        ON tenant_access_outbox_events; DROP FUNCTION IF EXISTS ${faultName}();`);
      installed = false;
    },
    async waitForInvitationState(_target, state) {
      return await poll(`INVITATION_${state}`, 180, invitation, value => value?.state === state);
    },
    async announceRetryReady() {
      writePrivate(resolve(output, 'invitation-retry-ready.json'),
        { runId: handoff.runId, phase: 'retry-in-Chrome-with-new-key', tenantId, invitationId });
    },
    async closeTenant(_tenantId, key) {
      const response = await api(handoff.apiOrigin, platformToken, 'POST',
        `/api/v1/platform/tenants/${tenantId}/closures`, key);
      return { status: response.status, code: response.code };
    },
    async installIamFault() {
      const existing = await row(pg, 'iam_db', `SELECT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname='${iamFaultName}') AS present`);
      assert.equal(existing.present, false, 'IAM_FAULT_ALREADY_EXISTS');
      await postgres(pg, 'iam_db', `BEGIN;
        CREATE FUNCTION ${iamFaultName}() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF EXISTS (SELECT 1 FROM iam_revocation_fences f
            WHERE f.revocation_request_id=NEW.revocation_request_id
              AND f.target_type='TENANT' AND f.target_id='${tenantId}')
            AND NEW.revocation_status='PENDING'
          THEN RAISE EXCEPTION 'STAGE4_SCOPED_IAM_REVOCATION_FAULT'; END IF;
          RETURN NEW;
        END $$;
        CREATE TRIGGER ${iamFaultName} BEFORE UPDATE ON iam_user_session_revocations
        FOR EACH ROW EXECUTE FUNCTION ${iamFaultName}(); COMMIT;`);
      iamFaultInstalled = true;
    },
    async removeIamFault() {
      if (!iamFaultInstalled) return;
      await postgres(pg, 'iam_db', `DROP TRIGGER IF EXISTS ${iamFaultName}
        ON iam_user_session_revocations; DROP FUNCTION IF EXISTS ${iamFaultName}();`);
      iamFaultInstalled = false;
    },
    async waitForLifecycleState(_tenantId, state) {
      return await poll(`LIFECYCLE_${state}`, 75, workflow, value => value?.state === state);
    },
    async recoverClosure(_tenantId, key) {
      const response = await api(handoff.apiOrigin, platformToken, 'POST',
        `/api/v1/platform/tenants/${tenantId}/closure-recoveries`, key);
      return { status: response.status, code: response.code };
    },
    async replayClosure(_tenantId, key) {
      return await api(handoff.apiOrigin, platformToken, 'POST',
        `/api/v1/platform/tenants/${tenantId}/closures`, key);
    },
    async replayRecovery(_tenantId, key) {
      return await api(handoff.apiOrigin, platformToken, 'POST',
        `/api/v1/platform/tenants/${tenantId}/closure-recoveries`, key);
    },
    async waitForClosed() {
      const closed = await poll('TENANT_CLOSED', 90, async () => {
        const value = await row(pg, 'tenant_access_db', `SELECT t.tenant_status AS status,
          w.revocation_request_id AS "requestId",
          (SELECT count(*)::integer FROM tenant_access_outbox_events e
            WHERE e.tenant_id='${tenantId}' AND e.event_snapshot->>'type'='com.saas.forge.tenant.closed.v1')
            AS "closedFacts" FROM tenants t JOIN tenant_lifecycle_workflows w ON w.tenant_id=t.id
          WHERE t.id='${tenantId}' AND w.lifecycle_action='CLOSE'
          ORDER BY w.created_at DESC LIMIT 1`);
        return value;
      }, value => value?.status === 'CLOSED');
      const old = await api(handoff.apiOrigin, oldTenantToken, 'GET', '/api/v1/organization/units');
      return { ...closed, oldTokenStatus: old.status };
    },
    async cleanup() {
      let failure;
      try { await result.removeInvitationFault(); }
      catch (error) { failure = error; }
      try { await result.removeIamFault(); }
      catch (error) { failure ??= error; }
      if (failure) throw failure;
    }
  };
  return result;
}

async function main() {
  const input = args(process.argv.slice(2));
  const handoff = JSON.parse(privateFile(input.run));
  const scenario = JSON.parse(privateFile(input.scenario));
  const target = validateFaultScenario(handoff, scenario, input.mode);
  const output = resolve(input.output);
  mkdirSync(output, { mode: 0o700 });
  let driver;
  let result;
  let failure;
  try {
    driver = await adapter(handoff, { ...target, platformTokenFile: scenario.platformTokenFile }, output);
    const pg = await onlyContainer(handoff.isolation.project, 'postgres');
    const tenant = await row(pg, 'tenant_access_db', `SELECT tenant_status AS status,
      created_at AS "createdAt", display_name AS "displayName"
      FROM tenants WHERE id='${identifier(target.tenantId)}'`);
    assert.equal(tenant?.status, 'ACTIVE', 'DISPOSABLE_TENANT_MUST_BE_ACTIVE');
    assert.ok(Date.parse(tenant.createdAt) >= Date.parse(handoff.startedAt), 'TENANT_NOT_FRESH');
    assert.equal(tenant.displayName, target.disposableDisplayName, 'DISPOSABLE_TENANT_NAME_MISMATCH');
    if (input.mode === 'invitation') {
      const invite = await row(pg, 'tenant_access_db', `SELECT invitation_status AS status FROM invitations
        WHERE id='${identifier(target.invitationId)}' AND tenant_id='${target.tenantId}'`);
      assert.equal(invite?.status, 'PENDING', 'INVITATION_NOT_PENDING');
    } else {
      const token = privateToken(target.oldTenantTokenFile);
      const pieces = token.split('.');
      assert.equal(pieces.length, 3, 'OLD_TENANT_TOKEN_REQUIRED');
      const claims = JSON.parse(Buffer.from(pieces[1], 'base64url').toString('utf8'));
      assert.equal(claims.tenantId, target.tenantId, 'OLD_TOKEN_TENANT_MISMATCH');
      assert.ok(Number.isInteger(claims.exp) && claims.exp > Date.now() / 1000 + 180,
        'OLD_TENANT_TOKEN_TOO_CLOSE_TO_EXPIRY');
      const before = await api(handoff.apiOrigin, token, 'GET', '/api/v1/organization/units');
      assert.equal(before.status, 200, 'OLD_TENANT_TOKEN_NOT_USABLE');
    }
    const key = () => {
      const milliseconds = Date.now();
      const bytes = Buffer.alloc(16);
      bytes.writeUIntBE(milliseconds, 0, 6);
      randomBytes(10).copy(bytes, 6);
      bytes[6] = (bytes[6] & 0x0f) | 0x70;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      const value = bytes.toString('hex');
      return `${value.slice(0,8)}-${value.slice(8,12)}-${value.slice(12,16)}-${value.slice(16,20)}-${value.slice(20)}`;
    };
    result = input.mode === 'invitation'
      ? await runInvitationCompensation(driver, target)
      : input.mode === 'closure'
        ? await runClosure(driver, target.tenantId, key())
        : await runClosureRecovery(driver, target.tenantId, key(), key());
  } catch (error) {
    failure = error;
  } finally {
    if (driver) {
      try { await driver.cleanup(); }
      catch (cleanupError) { failure = cleanupError; }
    }
    try {
      for (const frozen of handoff.backend.images) {
        assert.equal(await onlyContainer(handoff.isolation.project, frozen.service),
          frozen.containerId, `FROZEN_${frozen.service}_CONTAINER_DRIFT_AFTER_FAULT`);
      }
    } catch (identityError) {
      failure ??= identityError;
    }
  }
  writePrivate(resolve(output, failure ? 'failure.json' : 'result.json'), { schemaVersion: 1,
    kind: 'stage4-authority-fault-driver', runId: handoff.runId,
    mode: input.mode, status: failure ? 'failed' : 'passed',
    ...(failure ? { reason: failure.message } : { result }) });
  if (failure) throw failure;
  process.stdout.write(`stage4 authority ${input.mode}: passed\n`);
}

main().catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
