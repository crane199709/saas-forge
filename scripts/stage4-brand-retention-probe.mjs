#!/usr/bin/env node
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const roots = ['ACTIVE', 'DISABLED'];

function privateJson(path) {
  const stat = statSync(path);
  assert.ok(stat.isFile() && stat.size > 0 && stat.size < 524288 &&
    stat.uid === process.getuid() && (stat.mode & 0o077) === 0, 'PRIVATE_INPUT_REQUIRED');
  const bytes = readFileSync(path);
  return { value: JSON.parse(bytes), sha256: digest(bytes) };
}

function docker(args, input = '') {
  const result = spawnSync('docker', args, { input, encoding: 'utf8', timeout: 30000, maxBuffer: 524288 });
  assert.equal(result.error, undefined, 'DOCKER_UNAVAILABLE');
  return result;
}

function container(project, service, expectedId) {
  const found = docker(['ps', '-aq', '--filter', `label=com.docker.compose.project=${project}`,
    '--filter', `label=com.docker.compose.service=${service}`]);
  assert.equal(found.status, 0, 'DOCKER_CONTAINER_LIST_FAILED');
  const ids = found.stdout.trim().split(/\s+/).filter(Boolean);
  assert.equal(ids.length, 1, 'FRESH_CONTAINER_COUNT');
  const inspected = docker(['inspect', ids[0]]);
  assert.equal(inspected.status, 0, 'DOCKER_INSPECT_FAILED');
  const [details] = JSON.parse(inspected.stdout);
  assert.equal(details.State.Running, true, 'FRESH_CONTAINER_NOT_RUNNING');
  assert.equal(details.Config.Labels['com.docker.compose.project'], project);
  assert.equal(details.Config.Labels['com.docker.compose.service'], service);
  if (expectedId) assert.equal(details.Id, expectedId, 'FROZEN_CONTAINER_DRIFT');
  if (service === 'postgres') assert.ok(details.Mounts.some(mount =>
    mount.Type === 'volume' && mount.Name === `${project}_postgres-data`));
  return details.Id;
}

function assetRow(postgres, assetId) {
  assert.match(assetId, UUID);
  const query = `BEGIN READ ONLY;
    SELECT row_to_json(q) FROM (
      SELECT id, tenant_id AS "tenantId", object_key AS "objectKey", status,
        uploaded_at AS "uploadedAt", disabled_at AS "disabledAt",
        ever_bound_at AS "everBoundAt", retired_at AS "retiredAt",
        removed_at AS "removedAt", cleanup_reason AS "cleanupReason",
        (SELECT count(*)::integer FROM tenant_brand_profiles p
         WHERE p.tenant_id=a.tenant_id AND
           (p.logo_url='/brands/' || a.id::text OR p.favicon_url='/brands/' || a.id::text))
           AS "profileBindings"
      FROM tenant_brand_assets a WHERE id='${assetId}'
    ) q;
    COMMIT;`;
  const response = docker(['exec', '-i', postgres, 'sh', '-c',
    'exec psql -qAt -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname tenant_access_db',
    'stage4'], `${query}\n`);
  assert.equal(response.status, 0, 'BRAND_DB_QUERY_FAILED');
  const line = response.stdout.split('\n').find(value => value.startsWith('{'));
  assert.ok(line, 'BRAND_ROW_MISSING');
  return JSON.parse(line);
}

function s3ObjectState(storage, objectKey) {
  assert.match(objectKey, /^[A-Za-z0-9/_.-]{1,200}$/, 'OBJECT_KEY_INVALID');
  assert.ok(!objectKey.startsWith('/') && !objectKey.includes('..'), 'OBJECT_KEY_INVALID');
  const script = `set -eu
    IFS= read -r key
    user=$(cat /run/secrets/root-access-key)
    pass=$(cat /run/secrets/root-secret-key)
    MC_HOST_probe="http://\${user}:\${pass}@127.0.0.1:9000" mc stat --json "probe/sf-brand-assets/\$key"`;
  const result = docker(['exec', '-i', storage, 'sh', '-c', script], `${objectKey}\n`);
  const stat = JSON.parse(result.stdout);
  if (result.status === 0 && stat.status === 'success') return 'present';
  if (result.status === 1 && stat.status === 'error' &&
      stat.error?.cause?.message === 'Object does not exist') return 'absent';
  throw new Error('S3_STAT_INCONCLUSIVE');
}

async function publicStatus(clientDir, origin, assetId) {
  assert.equal(resolve(clientDir), clientDir, 'CLIENT_PATH_ABSOLUTE_REQUIRED');
  const metadata = JSON.parse(readFileSync(join(clientDir, 'package.json')));
  const source = JSON.parse(readFileSync(join(clientDir, 'contract-source.json')));
  assert.equal(metadata.name, '@crane199709/saas-forge-api-client');
  assert.equal(metadata.version, '0.6.0');
  assert.equal(source.dirty, false);
  const client = await import(pathToFileURL(join(clientDir, 'dist/index.js')).href);
  const api = new client.BrandAssetsApi(new client.Configuration({ basePath: origin }));
  try {
    await api.readPublicBrandAsset({ assetId }, { redirect: 'error', signal: AbortSignal.timeout(15000) });
    return 200;
  } catch (error) {
    if (error instanceof client.ResponseError) return error.response.status;
    throw error;
  }
}

async function main(handoffPath, baselinePath, mode, clientDir, outputPath) {
  assert.ok(roots.includes(mode), 'RETENTION_MODE_INVALID');
  const handoff = privateJson(handoffPath);
  const baseline = privateJson(baselinePath);
  const h = handoff.value, b = baseline.value;
  assert.equal(h.schemaVersion, 1);
  assert.equal(h.status, 'ready');
  assert.equal(h.isolation?.kind, 'fresh-compose');
  assert.equal(h.isolation.project, `sf-acceptance-${h.runId}`);
  assert.ok(Date.now() >= Date.parse(h.readyAt) && Date.now() < Date.parse(h.expiresAt));
  assert.equal(b.runId, h.runId);
  assert.equal(b.handoffSha256, handoff.sha256);
  assert.equal(b.records?.length, 2);
  const [record] = b.records.filter(item => item.status === mode);
  assert.ok(record && b.records.filter(item => item.status === mode).length === 1,
    'BASELINE_ASSET_COUNT');
  assert.match(record.assetId, UUID);
  assert.equal(record.everBoundAt, null);
  assert.equal(record.retiredAt, null);
  const dueAt = new Date(Date.parse(mode === 'ACTIVE' ? record.uploadedAt : record.disabledAt) +
    (mode === 'ACTIVE' ? 24 : 7 * 24) * 60 * 60 * 1000);
  assert.ok(Number.isFinite(dueAt.getTime()), 'RETENTION_TIME_INVALID');
  const report = { schemaVersion: 1, kind: 'stage4-brand-retention', runId: h.runId,
    handoffSha256: handoff.sha256, baselineSha256: baseline.sha256, mode,
    assetSha256: digest(`${h.runId}:${record.assetId}`), dueAt: dueAt.toISOString(),
    observedAt: new Date().toISOString(), status: 'failed' };
  try {
    for (const frozen of h.backend.images) container(h.isolation.project, frozen.service, frozen.containerId);
    const pg = container(h.isolation.project, 'postgres');
    const storage = container(h.isolation.project, 'brand-storage');
    const row = assetRow(pg, record.assetId);
    assert.equal(row.id, record.assetId);
    assert.equal(digest(row.objectKey), record.objectKeySha256, 'OBJECT_KEY_DRIFT');
    assert.equal(row.profileBindings, 0, 'ASSET_BECAME_BOUND');
    assert.equal(row.everBoundAt, null, 'ASSET_WAS_BOUND');
    assert.equal(row.retiredAt, null, 'ASSET_WAS_RETIRED');
    assert.equal(Date.parse(row.uploadedAt), Date.parse(record.uploadedAt), 'UPLOAD_TIME_DRIFT');
    assert.equal(Date.parse(row.disabledAt), Date.parse(record.disabledAt), 'DISABLE_TIME_DRIFT');
    const objectState = s3ObjectState(storage, row.objectKey);
    const apiStatus = await publicStatus(clientDir, h.apiOrigin, record.assetId);
    report.databaseStatus = row.status;
    report.s3ObjectState = objectState;
    report.publicHttpStatus = apiStatus;
    if (Date.now() < dueAt.getTime() + 1000) {
      assert.equal(row.status, mode, 'ASSET_REMOVED_BEFORE_DUE');
      assert.equal(objectState, 'present', 'OBJECT_MISSING_BEFORE_DUE');
      assert.equal(apiStatus, mode === 'ACTIVE' ? 200 : 404, 'PUBLIC_POLICY_BEFORE_DUE');
      report.status = 'waiting';
    } else {
      assert.equal(row.status, 'REMOVED', 'RETENTION_NOT_REMOVED');
      assert.equal(row.cleanupReason, mode === 'ACTIVE' ? 'UNBOUND' : 'DISABLED');
      assert.ok(Date.parse(row.removedAt) >= dueAt.getTime(), 'REMOVED_BEFORE_DUE');
      assert.equal(objectState, 'absent', 'OBJECT_NOT_DELETED');
      assert.equal(apiStatus, 404, 'PUBLIC_READ_NOT_404');
      report.removedAt = row.removedAt;
      report.cleanupReason = row.cleanupReason;
      report.status = 'passed';
    }
  } catch (error) {
    report.reason = /^[A-Z0-9_]{3,90}$/.test(error?.message ?? '')
      ? error.message : 'RETENTION_PROBE_REJECTED';
    process.exitCode = 1;
  } finally {
    report.finishedAt = new Date().toISOString();
    writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600, flag: 'wx' });
    console.log(JSON.stringify({ status: report.status, mode, reason: report.reason }));
  }
}

if (process.argv.length !== 7) throw new Error('USAGE');
await main(...process.argv.slice(2));
