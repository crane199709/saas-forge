import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, renameSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

// 环境侧只调整本轮 Console 创建的 Client 的旧 Secret 时间；进程退出或租约到期恢复原值。
const [handoffFile, directory] = process.argv.slice(2);
assert.equal(statSync(directory).mode & 0o077, 0);
const handoff = JSON.parse(readFileSync(handoffFile, 'utf8'));
assert.equal(handoff.status, 'ready');
assert.equal(handoff.isolation.kind, 'fresh-compose');
assert.equal(handoff.isolation.project, `sf-acceptance-${handoff.runId}`);
assert.ok(Date.parse(handoff.expiresAt) > Date.now());
const docker = (...args) => execFileSync('docker', args, {
  encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000
}).trim();
const ids = docker('ps', '-q', '--filter', `label=com.docker.compose.project=${handoff.isolation.project}`,
  '--filter', 'label=com.docker.compose.service=postgres').split(/\s+/).filter(Boolean);
assert.equal(ids.length, 1);
function inspect() {
  const instance = JSON.parse(docker('inspect', ids[0]))[0];
  assert.equal(instance.Config.Labels['com.docker.compose.project'], handoff.isolation.project);
  assert.equal(instance.Config.Labels['com.docker.compose.service'], 'postgres');
  assert.ok(Date.parse(instance.Created) >= Date.parse(handoff.startedAt));
  assert.ok(instance.Mounts.some(m => m.Name === `${handoff.isolation.project}_postgres-data`));
  assert.equal(instance.State.Health.Status, 'healthy');
}
function query(sql) {
  inspect();
  return docker('exec', ids[0], 'sh', '-c',
    'exec psql --username "$POSTGRES_USER" --dbname iam_db -v ON_ERROR_STOP=1 -At -c "$1"', 'psql', sql);
}
function uuid(value) { assert.match(value, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/); return value; }
function timestamp(value) { assert.match(value, /^[0-9T:.+Z -]+$/); assert.ok(Number.isFinite(Date.parse(value))); return value; }
function mutate(sql) {
  query(`DO $guard$ DECLARE affected integer; BEGIN ${sql}; GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'Unexpected row count'; END IF; END $guard$;`);
}
const records = [];
let saved, previous, verifiedClient, done = false;
const record = (state, facts) => {
  records.push({ state, at: new Date().toISOString(), ...facts });
  writeFileSync(resolve(directory, 'injections.json'), JSON.stringify({
    runId: handoff.runId, actualWait24Hours: false, records
  }, null, 2), { mode: 0o600 });
};
function restore() {
  if (!saved) return;
  const { id, clientId, original, injected, alias } = saved;
  const unchanged = query(`SELECT valid_until='${original}' FROM iam_oauth_client_secrets WHERE id='${id}' AND client_id='${clientId}'`);
  if (unchanged === 't') { saved = undefined; record('unchanged', { client: alias }); return; }
  mutate(`UPDATE iam_oauth_client_secrets SET valid_until='${original}' WHERE id='${id}'
    AND client_id='${clientId}' AND valid_until='${injected}' AND revoked_at IS NULL`);
  assert.equal(query(`SELECT valid_until='${original}' FROM iam_oauth_client_secrets WHERE id='${id}'`), 't');
  record('restored', { client: alias, affectedRows: 1, original });
  saved = undefined;
}
process.on('SIGINT', () => { done = true; });
process.on('SIGTERM', () => { done = true; });
try {
  while (!done && Date.now() < Date.parse(handoff.expiresAt)) {
    let request;
    try { request = JSON.parse(readFileSync(resolve(directory, 'request.json'), 'utf8')); } catch { /* 等待完整请求。 */ }
    if (request && request.requestId !== previous) {
      assert.equal(request.runId, handoff.runId);
      assert.match(request.requestId, /^[0-9a-f]{32}$/);
      assert.ok(['expired', 'restored', 'logs'].includes(request.state));
      const clientId = uuid(request.clientId);
      if (request.state === 'expired') {
        assert.equal(saved, undefined);
        const client = JSON.parse(query(`SELECT row_to_json(c) FROM (SELECT display_name,created_at,client_type,client_status
          FROM iam_oauth_clients WHERE id='${clientId}') c`));
        assert.ok(client.display_name.includes(handoff.runId));
        assert.ok(Date.parse(client.created_at) >= Date.parse(handoff.startedAt));
        assert.equal(client.client_type, 'RUNTIME_SERVICE');
        assert.equal(client.client_status, 'ACTIVE');
        const rows = JSON.parse(query(`SELECT json_agg(s) FROM (SELECT id,created_at,valid_until
          FROM iam_oauth_client_secrets WHERE client_id='${clientId}' AND revoked_at IS NULL AND valid_until IS NOT NULL) s`));
        assert.equal(rows.length, 1);
        const row = rows[0], original = timestamp(row.valid_until);
        const injected = timestamp(query("SELECT clock_timestamp()-interval '1 second'"));
        assert.ok(Date.parse(original) > Date.now());
        assert.ok(Date.parse(injected) > Date.parse(row.created_at));
        const alias = createHash('sha256').update(clientId).digest('hex');
        // 先留下恢复信息，再修改数据库；未知写入结果不会被报告为成功。
        const candidate = { id: uuid(row.id), clientId, original, injected, alias, at: Date.now() };
        writeFileSync(resolve(directory, 'restore.json'), JSON.stringify(candidate), { mode: 0o600 });
        saved = candidate;
        verifiedClient = clientId;
        mutate(`UPDATE iam_oauth_client_secrets SET valid_until='${injected}' WHERE id='${candidate.id}'
          AND client_id='${clientId}' AND valid_until='${original}' AND revoked_at IS NULL`);
        record('expired', { client: alias, original, injected, affectedRows: 1 });
      } else if (request.state === 'restored') {
        assert.ok(saved);
        assert.equal(clientId, saved.clientId);
        restore();
      } else {
        assert.equal(saved, undefined);
        assert.equal(clientId, verifiedClient);
        const services = ['gateway', 'iam-service', 'tenant-access-service', 'entitlement-service', 'audit-service', 'platform-mechanism-receiver'];
        const logs = [];
        for (const service of services) {
          const containers = docker('ps', '-q', '--filter', `label=com.docker.compose.project=${handoff.isolation.project}`,
            '--filter', `label=com.docker.compose.service=${service}`).split(/\s+/).filter(Boolean);
          assert.equal(containers.length, 1);
          const result = spawnSync('docker', ['logs', '--since', handoff.startedAt, containers[0]], {
            encoding: 'utf8', timeout: 30000, maxBuffer: 32 * 1024 * 1024
          });
          assert.equal(result.status, 0);
          logs.push(result.stdout, result.stderr);
        }
        writeFileSync(resolve(directory, 'service-logs.txt'), logs.join('\n'), { mode: 0o600, flag: 'wx' });
        record('logs-collected', { services });
        done = true;
      }
      const result = { runId: handoff.runId, requestId: request.requestId, state: request.state, status: 'passed' };
      writeFileSync(resolve(directory, 'response.tmp'), JSON.stringify(result), { mode: 0o600 });
      renameSync(resolve(directory, 'response.tmp'), resolve(directory, 'response.json'));
      previous = request.requestId;
    }
    if (saved && Date.now() - saved.at > 120000) throw new Error('INJECTION_LEASE_EXPIRED');
    await delay(250);
  }
} catch {
  process.exitCode = 1;
  record('failed', {});
} finally {
  try { restore(); } catch {
    process.exitCode = 1;
    record('restoration-unconfirmed', {});
  }
}
