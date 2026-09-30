import { test } from 'node:test';
import assert from 'node:assert/strict';
import { classifyRuntimeError, errorBlocks } from '../stage2-runtime-classification.mjs';
const traceId = '1234567890abcdef1234567890abcdef';
const security = { redisStopped: { startedAt: '2026-09-29T13:38:00Z' }, redisRestored: { finishedAt: '2026-09-29T13:40:00Z' },
  requests: [{ phase: 'redis-failure', at: Date.parse('2026-09-29T13:39:11Z'), status: 503, code: 'TOKEN_REVOCATION_STATUS_UNAVAILABLE' },
    { phase: 'redis-failure', at: Date.parse('2026-09-29T13:39:11Z'), status: 503, code: 'SESSION_SECURITY_UNAVAILABLE', traceId }] };
const head = '2026-09-29T13:39:11.301Z 2026-09-29T13:39:11.246Z ERROR 1 --- [iam-service] [scheduling-1] ';
const scheduled = `${head}o.s.s.s.TaskUtils$LoggingErrorHandler : Unexpected error occurred in scheduled task\n2026-09-29T13:39:11.302Z io.saas.forge.iam.application.authentication.RevocationIndexUnavailableException: unavailable\n2026-09-29T13:39:11.303Z at io.saas.forge.iam.application.authentication.RevocationIndexRecovery.recoverIfNeeded(RevocationIndexRecovery.java:38)\n2026-09-29T13:39:11.304Z Caused by: io.lettuce.core.RedisCommandTimeoutException: timed out`;
const consoleError = `${head}.a.ConsoleAuthenticationExceptionHandler : Console authentication unavailable: code=SESSION_SECURITY_UNAVAILABLE traceId=${traceId} exception=io.saas.forge.iam.application.authentication.RevocationIndexUnavailableException`;
test('retains complete exception blocks and requires exact Redis scenario evidence', () => {
  const blocks = errorBlocks(`${scheduled}\n${consoleError}`);
  assert.equal(blocks.length, 2);
  assert.equal(classifyRuntimeError('iam-service', blocks[0], security).scenario, 'redis-recovery-scheduler-during-injection');
  assert.equal(classifyRuntimeError('iam-service', blocks[1], security).requestTrace, traceId);
  for (const block of [consoleError.replace(traceId, '0'.repeat(32)), consoleError.replace('RevocationIndexUnavailableException', 'IllegalStateException'), scheduled.replace('recoverIfNeeded', 'otherJob')]) {
    assert.equal(classifyRuntimeError('iam-service', block, security), undefined);
  }
  assert.equal(classifyRuntimeError('iam-service', scheduled, { ...security, requests: [] }), undefined);
  assert.equal(classifyRuntimeError('audit-service', scheduled, security), undefined);
  assert.equal(classifyRuntimeError('iam-service', scheduled, { ...security, requests: security.requests.map(r => ({ ...r, at: 0 })) }), undefined);
  assert.equal(classifyRuntimeError('iam-service', consoleError, { ...security, redisRestored: { finishedAt: '2026-09-29T13:39:00Z' } }), undefined);
});

test('unknown log formats cannot hide ERROR behind an INFO record', () => {
  const blocks = errorBlocks('2026-09-29T13:39:00Z 2026-09-29T13:39:00Z INFO ready\n2026-09-29T13:39:01Z ERROR unclassified');
  assert.equal(blocks.length, 1);
  assert.equal(classifyRuntimeError('iam-service', blocks[0], security), undefined);
});

test('refresh lease unavailability requires the matching refresh POST trace during injection', () => {
  const block = consoleError.replace('RevocationIndexUnavailableException', 'RefreshRotationUnavailableException');
  const evidence = { ...security, requests: [{ ...security.requests[1], method: 'POST', path: '/api/v2/auth/refresh' }] };
  assert.equal(classifyRuntimeError('iam-service', block, evidence)?.requestTrace, traceId);
  for (const change of [{ method: 'GET' }, { path: '/api/v2/auth/session' }, { traceId: '0'.repeat(32) }]) {
    assert.equal(classifyRuntimeError('iam-service', block, { ...evidence, requests: [{ ...evidence.requests[0], ...change }] }), undefined);
  }
});
