import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

const script = readFileSync(new URL('../verify-tenant-lifecycle-e2e.sh', import.meta.url), 'utf8');
const waitFunction = script.match(/^wait_for_service_started\(\) \{\n[\s\S]*?^\}/m)?.[0];
assert.ok(waitFunction);

// 执行验收脚本的实际等待逻辑；替身只模拟容器与网络边界，不启动完整环境。
function run(service, status, state = 'running', log = 'Application diagnostic') {
  return spawnSync('bash', ['-c', `
set -euo pipefail
compose() {
  case "$1" in
    port) printf '127.0.0.1:18080\\n' ;;
    logs) printf '%s\\n' "$LOG" ;;
    ps) printf '{"Service":"%s","State":"%s"}\\n' "$SERVICE" "$STATE" ;;
  esac
}
curl() {
  for argument in "$@"; do :; done
  path=/actuator/health/readiness
  if [[ "$SERVICE" == gateway ]]; then path=/.well-known/jwks.json; fi
  [[ "$argument" == "http://127.0.0.1:18080$path" ]] || exit 99
  printf '%s' "$STATUS"
}
seq() { printf '1\\n'; }
sleep() { :; }
${waitFunction}
wait_for_service_started "$SERVICE"
`], { encoding: 'utf8', env: { ...process.env, SERVICE: service, STATUS: status, STATE: state, LOG: log } });
}

for (const service of ['audit-service', 'gateway']) {
  test(`${service} accepts readiness without plaintext startup logs`, () => {
    const result = run(service, '200');
    assert.equal(result.status, 0, result.stderr);
  });
  test(`${service} rejects unavailable readiness even with a stale startup log`, () => {
    for (const status of ['000', '503']) {
      assert.notEqual(run(service, status, 'running', 'Started ExampleApplication').status, 0);
    }
    assert.match(run(service, '000', 'exited').stderr, /已退出/);
  });
}

test('unchanged services retain startup and exited-container checks', () => {
  assert.equal(run('iam-service', '000', 'running', 'Started IamServiceApplication').status, 0);
  assert.notEqual(run('iam-service', '200').status, 0);
  assert.match(run('iam-service', '000', 'exited').stderr, /已退出/);
});
