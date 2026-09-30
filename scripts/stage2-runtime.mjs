import { classifyRuntimeError, errorBlocks } from './stage2-runtime-classification.mjs';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

// 仅输出错误摘要的散列和时间关联；完整服务日志留在环境方的受限目录，不进入验收产物。
const [handoffPath, securityPath, oauthPath, output] = process.argv.slice(2);
const bytes = readFileSync(handoffPath), handoff = JSON.parse(bytes);
const hash = value => createHash('sha256').update(value).digest('hex');
const report = { schemaVersion: 1, kind: 'stage2-runtime-errors', runId: handoff.runId, handoffSha256: hash(bytes),
  driverSha256: Object.fromEntries(['stage2-runtime.mjs', 'stage2-runtime-classification.mjs'].map(name => [name, hash(readFileSync(new URL(name, import.meta.url)))])),
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
    for (const block of errorBlocks(`${logs.stdout}\n${logs.stderr}`)) {
      const at = block.split(' ')[0];
      const matched = classifyRuntimeError(service, block.trimEnd(), security);
      report.errors.push({ service, at, sha256: hash(block), scenario: matched?.scenario ?? 'unclassified',
        expected: Boolean(matched), ...(matched?.requestTrace ? { requestTrace: hash(matched.requestTrace) } : {}) });
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
