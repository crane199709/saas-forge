  import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

// 环境方单独运行；只控制 handoff 指定项目已经存在的唯一 Redis，不删除数据或接管其他实例。
const [handoffFile, directory] = process.argv.slice(2);
const handoff = JSON.parse(readFileSync(handoffFile, 'utf8'));
assert.equal(handoff.status, 'ready');
assert.equal(handoff.isolation.kind, 'fresh-compose');
assert.equal(handoff.isolation.project, `sf-acceptance-${handoff.runId}`);
assert.ok(Date.parse(handoff.expiresAt) > Date.now());
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 60000 }).trim();
const ids = docker('ps', '-aq', '--filter', `label=com.docker.compose.project=${handoff.isolation.project}`,
  '--filter', 'label=com.docker.compose.service=redis').split(/\s+/).filter(Boolean);
assert.equal(ids.length, 1);
const id = ids[0];
function inspect() {
  const instance = JSON.parse(docker('inspect', id))[0];
  assert.equal(instance.Config.Labels['com.docker.compose.project'], handoff.isolation.project);
  assert.equal(instance.Config.Labels['com.docker.compose.service'], 'redis');
  assert.ok(Date.parse(instance.Created) >= Date.parse(handoff.startedAt));
  assert.ok(instance.Mounts.some(mount => mount.Name === `${handoff.isolation.project}_redis-data`));
  return instance;
}
assert.equal(inspect().State.Health.Status, 'healthy');
let stopped = false, done = false, previous, stoppedAt;
process.on('SIGINT', () => { done = true; });
process.on('SIGTERM', () => { done = true; });
async function restore() {
  inspect();
  docker('start', id);
  for (let n = 0; n < 60; n++) {
    if (inspect().State.Health.Status === 'healthy') { stopped = false; return; }
    await delay(1000);
  }
  throw new Error('REDIS_RESTORE_TIMEOUT');
}
try {
  while (!done && Date.now() < Date.parse(handoff.expiresAt)) {
    let request;
    try { request = JSON.parse(readFileSync(resolve(directory, 'request.json'), 'utf8')); } catch { /* 等待完整请求。 */ }
    if (request && request.requestId !== previous) {
      assert.equal(request.runId, handoff.runId);
      assert.match(request.requestId, /^[0-9a-f]{32}$/);
      assert.ok(['stopped', 'healthy'].includes(request.state));
      const startedAt = new Date().toISOString();
      inspect();
      if (request.state === 'stopped') {
        assert.equal(stopped, false);
        stopped = true;
        stoppedAt = Date.now();
        docker('stop', '--time', '5', id);
        assert.equal(inspect().State.Running, false);
      } else { await restore(); }
      const result = { ...request, status: 'passed', startedAt, finishedAt: new Date().toISOString() };
      const temporary = resolve(directory, 'response.tmp');
      writeFileSync(temporary, JSON.stringify(result), { mode: 0o600 });
      renameSync(temporary, resolve(directory, 'response.json'));
      previous = request.requestId;
      if (request.state === 'healthy') done = true;
    }
    // 浏览器崩溃或失联后仍有界恢复，不能把停止状态留到交接过期。
    if (stopped && Date.now() - stoppedAt > 240000) throw new Error('FAULT_LEASE_EXPIRED');
    await delay(250);
  }
} finally { if (stopped) await restore(); }
