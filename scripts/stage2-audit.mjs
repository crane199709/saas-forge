import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

// 只读关联本轮浏览器观察、已发布 Outbox 和 Audit，不以历史表中存在任意记录作为通过依据。
const [handoffPath, observationsPath, output] = process.argv.slice(2);
const bytes = readFileSync(handoffPath), handoff = JSON.parse(bytes);
const hash = value => createHash('sha256').update(value).digest('hex');
const report = { schemaVersion: 1, kind: 'stage2-audit', runId: handoff.runId, handoffSha256: hash(bytes),
  startedAt: new Date().toISOString(), status: 'failed', checks: [] };
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 }).trim();
try {
  assert.equal(handoff.status, 'ready');
  assert.equal(handoff.isolation.kind, 'fresh-compose');
  assert.equal(handoff.isolation.project, `sf-acceptance-${handoff.runId}`);
  assert.ok(Date.now() < Date.parse(handoff.expiresAt));
  const observationsBytes = readFileSync(observationsPath);
  const observations = JSON.parse(observationsBytes);
  report.observationsSha256 = hash(observationsBytes);
  assert.equal(observations.runId, handoff.runId);
  const ids = docker('ps', '-q', '--filter', `label=com.docker.compose.project=${handoff.isolation.project}`,
    '--filter', 'label=com.docker.compose.service=postgres').split(/\s+/).filter(Boolean);
  assert.equal(ids.length, 1);
  const instance = JSON.parse(docker('inspect', ids[0]))[0];
  assert.ok(Date.parse(instance.Created) >= Date.parse(handoff.startedAt));
  assert.ok(instance.Mounts.some(m => m.Name === `${handoff.isolation.project}_postgres-data`));
  const query = (database, sql) => JSON.parse(docker('exec', ids[0], 'sh', '-c',
    'exec psql --username "$POSTGRES_USER" --dbname "$1" -v ON_ERROR_STOP=1 -At -c "$2"', 'psql', database,
    `BEGIN READ ONLY; ${sql}; COMMIT`).split('\n').filter(line => line.startsWith('[') || line.startsWith('{')).join('\n'));
  for (const action of ['SESSION_STARTED', 'TENANT_CREATED', 'TENANT_CONTEXT_SWITCHED']) {
    const candidates = observations.observations.filter(o => o.action === action
      && (action !== 'TENANT_CONTEXT_SWITCHED' || o.phase === 'stage2-context-switch'));
    assert.ok(candidates.length > 0);
    const observed = candidates.at(-1);
    for (const value of [observed.actor, observed.resource, ...(observed.membership ? [observed.membership] : [])]) {
      assert.match(value, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    }
    // 同一 Session 会多次切换；绑定页面目标 Membership 和该请求时间窗，不能取任意首条历史事件。
    const from = new Date(Date.parse(observed.startedAt) - 2000).toISOString();
    const to = new Date(Date.parse(observed.finishedAt) + 2000).toISOString();
    const target = action === 'TENANT_CONTEXT_SWITCHED' ? ` AND metadata->>'targetMembershipId'='${observed.membership}'` : '';
    let matched;
    for (let attempt = 0; attempt < 60 && !matched; attempt += 1) {
      const rows = query('audit_db', `SELECT COALESCE(json_agg(a),'[]'::json) FROM (SELECT source_event_id,actor_identity_id,resource_id,trace_id FROM audit_records WHERE action='${action}' AND occurred_at BETWEEN '${from}' AND '${to}' AND actor_identity_id='${observed.actor}' AND resource_id='${observed.resource}'${target}) a`);
      assert.ok(rows.length <= 1, 'AUDIT_OPERATION_AMBIGUOUS');
      if (rows.length === 1) {
        const row = rows[0];
        assert.match(row.source_event_id, /^[0-9a-f-]{36}$/);
        const tenant = action === 'TENANT_CREATED';
        const events = query(tenant ? 'tenant_access_db' : 'iam_db', `SELECT COALESCE(json_agg(o),'[]'::json) FROM (SELECT event_snapshot,published_at FROM ${tenant ? 'tenant_access' : 'iam'}_outbox_events WHERE event_id='${row.source_event_id}') o`);
        if (events.length === 1 && events[0].published_at) matched = { row, event: events[0].event_snapshot };
      }
      if (!matched) await delay(1000);
    }
    assert.ok(matched, 'AUDIT_OR_OUTBOX_TIMEOUT');
    const { row, event } = matched;
    assert.match(row.trace_id, /^[0-9a-f]{32}$/);
    assert.equal(event.id, row.source_event_id);
    assert.equal(event.traceId, row.trace_id);
    assert.equal(event.subject, row.resource_id);
    assert.equal(event.data.actorIdentityId ?? event.data.identityId, row.actor_identity_id);
    report.checks.push({ name: action, status: 'passed', event: hash(row.source_event_id), actor: hash(row.actor_identity_id),
      resource: hash(row.resource_id), trace: hash(row.trace_id), published: true, matchedBrowserResource: true,
      browserWindow: { from: observed.startedAt, to: observed.finishedAt },
      ...(observed.membership ? { targetMembership: hash(observed.membership) } : {}), eventActorResourceTraceMatch: true });
  }
  report.classification = { unknownErrors: 0, unexpectedRequests: 0 };
  report.status = 'passed';
} catch (error) {
  report.failure = { kind: error.name };
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  writeFileSync(output, JSON.stringify(report, null, 2), { mode: 0o600, flag: 'wx' });
  console.log(JSON.stringify({ status: report.status, checks: report.checks.length }));
}
