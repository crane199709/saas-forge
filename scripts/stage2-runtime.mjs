import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

// 仅输出错误摘要的散列和时间关联；完整服务日志留在环境方的受限目录，不进入验收产物。
const [handoffPath, securityPath, oauthPath, output] = process.argv.slice(2);
const bytes = readFileSync(handoffPath), handoff = JSON.parse(bytes);
const hash = value => createHash('sha256').update(value).digest('hex');
const report = { schemaVersion: 1, kind: 'stage2-runtime-errors', runId: handoff.runId, handoffSha256: hash(bytes),
  startedAt: new Date().toISOString(), status: 'failed', checks: [], errors: [] };
try {
  assert.equal(handoff.status, 'ready');
  assert.equal(handoff.isolation.kind, 'fresh-compose');
  assert.equal(handoff.isolation.project, `sf-acceptance-${handoff.runId}`);
  const security = JSON.parse(readFileSync(securityPath)), oauth = JSON.parse(readFileSync(oauthPath));
  for (const source of [security, oauth]) {
    assert.equal(source.runId, handoff.runId);
    assert.equal(source.handoffSha256, hash(bytes));
    assert.ok(Number.isFinite(Date.parse(source.startedAt)) && Number.isFinite(Date.parse(source.finishedAt)));
  }
  const since = new Date(Math.min(Date.parse(security.startedAt), Date.parse(oauth.startedAt))).toISOString();
  const until = new Date(Math.max(Date.parse(security.finishedAt), Date.parse(oauth.finishedAt))).toISOString();
  for (const service of ['gateway', 'iam-service', 'tenant-access-service', 'entitlement-service', 'audit-service', 'platform-mechanism-receiver']) {
    const ids = execFileSync('docker', ['ps', '-q', '--filter', `label=com.docker.compose.project=${handoff.isolation.project}`,
      '--filter', `label=com.docker.compose.service=${service}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 }).trim().split(/\s+/).filter(Boolean);
    assert.equal(ids.length, 1);
    const logs = spawnSync('docker', ['logs', '--timestamps', '--since', since, '--until', until, ids[0]], {
      encoding: 'utf8', timeout: 30000, maxBuffer: 32 * 1024 * 1024
    });
    assert.equal(logs.status, 0);
    for (const line of `${logs.stdout}\n${logs.stderr}`.split('\n').filter(value => /\bERROR\b/.test(value))) {
      const at = line.split(' ')[0];
      const inFault = Date.parse(at) >= Date.parse(security.redisStopped?.startedAt) && Date.parse(at) <= Date.parse(security.redisRestored?.finishedAt);
      const request = security.requests.find(request => request.phase === 'redis-failure' && request.status === 503
        && request.code === 'TOKEN_REVOCATION_STATUS_UNAVAILABLE' && /^[0-9a-f]{32}$/.test(request.traceId)
        && line.includes(request.traceId));
      const redisFault = inFault && Boolean(request) && /TOKEN_REVOCATION_STATUS_UNAVAILABLE/.test(line);
      report.errors.push({ service, at, sha256: hash(line), scenario: redisFault ? 'redis-failure' : 'unclassified', expected: redisFault, ...(redisFault ? { requestTrace: hash(request.traceId) } : {}) });
    }
  }
  const unknownErrors = report.errors.filter(error => !error.expected).length;
  report.classification = { unknownErrors, unexpectedRequests: 0 };
  assert.equal(unknownErrors, 0, 'UNKNOWN_RUNTIME_ERROR');
  report.checks.push({ name: 'scenario-correlated-runtime-errors', status: 'passed', securitySha256: hash(readFileSync(securityPath)), oauthSha256: hash(readFileSync(oauthPath)) });
  report.status = 'passed';
} catch (error) {
  report.failure = { kind: error.name };
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  writeFileSync(output, JSON.stringify(report, null, 2), { mode: 0o600, flag: 'wx' });
  console.log(JSON.stringify({ status: report.status, errors: report.errors.length }));
}
