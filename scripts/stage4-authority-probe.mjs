import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

export function uuidV7(now = Date.now(), random = randomBytes(10)) {
  assert.ok(Number.isSafeInteger(now) && now >= 0 && random.length === 10);
  const bytes = Buffer.alloc(16);
  bytes.writeUIntBE(now, 0, 6);
  random.copy(bytes, 6);
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function privateFile(path, limit = 524288) {
  const stat = statSync(path);
  assert.ok(stat.isFile() && stat.size > 0 && stat.size <= limit &&
    stat.uid === process.getuid() && stat.gid === process.getgid() &&
    (stat.mode & 0o077) === 0, 'PRIVATE_FILE_REQUIRED');
  return readFileSync(path);
}

function secret(path) {
  const value = privateFile(path, 8192).toString('utf8').trim();
  assert.ok(value.length >= 12 && !/[\r\n]/.test(value), 'SECRET_FILE_INVALID');
  return value;
}

function requireUuid(value) {
  assert.match(value, UUID_V7, 'UUID_V7_REQUIRED');
  return value;
}

export function validateScenario(handoff, scenario) {
  assert.equal(handoff.schemaVersion, 1);
  assert.equal(handoff.status, 'ready');
  assert.equal(handoff.isolation?.kind, 'fresh-compose');
  assert.equal(handoff.isolation.project, `sf-acceptance-${handoff.runId}`);
  assert.ok(handoff.requiredServices?.includes('project-service'));
  assert.ok(Date.now() >= Date.parse(handoff.readyAt) && Date.now() < Date.parse(handoff.expiresAt));
  assert.equal(new URL(handoff.apiOrigin).origin, handoff.apiOrigin);
  assert.equal(new URL(handoff.apiOrigin).protocol, 'https:');
  assert.match(handoff.jwksSha256, /^[0-9a-f]{64}$/);
  if (handoff.edge) assert.ok(handoff.edge.host === '127.0.0.1' &&
    Number.isInteger(handoff.edge.port) && handoff.edge.port > 0 && handoff.edge.port <= 65535);
  assert.equal(scenario.runId, handoff.runId);
  assert.deepEqual(Object.keys(scenario).sort(),
    ['runId', 'tenantA', 'tenantB', 'lastAdministrators', 'projectAppPasswordFile',
      'tenantAccessAppPasswordFile'].sort());
  for (const tenant of [scenario.tenantA, scenario.tenantB]) {
    assert.deepEqual(Object.keys(tenant).sort(), ['tenantId', 'tokenFile', 'organizationUnitId',
      'roleId', 'membershipId', 'brandDisplayName'].sort());
    requireUuid(tenant.tenantId);
    for (const key of ['organizationUnitId', 'roleId', 'membershipId']) requireUuid(tenant[key]);
    assert.ok(typeof tenant.brandDisplayName === 'string' && tenant.brandDisplayName.trim());
    assert.equal(typeof tenant.tokenFile, 'string');
  }
  assert.notEqual(scenario.tenantA.tenantId, scenario.tenantB.tenantId);
  for (const key of ['organizationUnitId', 'roleId', 'membershipId', 'brandDisplayName'])
    assert.notEqual(scenario.tenantA[key], scenario.tenantB[key], 'TENANT_SENTINELS_NOT_DISTINCT');
  const race = scenario.lastAdministrators;
  assert.deepEqual(Object.keys(race).sort(), ['tenantId', 'administratorRoleId', 'first', 'second'].sort());
  requireUuid(race.tenantId);
  requireUuid(race.administratorRoleId);
  for (const member of [race.first, race.second]) {
    assert.deepEqual(Object.keys(member).sort(), ['membershipId', 'tokenFile']);
    requireUuid(member.membershipId);
  }
  assert.notEqual(race.first.membershipId, race.second.membershipId);
  assert.equal(typeof scenario.projectAppPasswordFile, 'string');
  assert.equal(typeof scenario.tenantAccessAppPasswordFile, 'string');
}

function command(name, args, input, timeout = 30000) {
  return new Promise((resolveOutput, reject) => {
    const child = spawn(name, args, { stdio: ['pipe', 'pipe', 'pipe'] });
    const stdout = [];
    const timer = setTimeout(() => child.kill('SIGKILL'), timeout);
    child.stdout.on('data', chunk => stdout.push(chunk));
    child.stderr.resume();
    child.on('error', error => { clearTimeout(timer); reject(error); });
    child.on('close', code => {
      clearTimeout(timer);
      if (code !== 0) reject(new Error(`${name.toUpperCase()}_FAILED`));
      else resolveOutput(Buffer.concat(stdout).toString('utf8'));
    });
    child.stdin.end(input);
  });
}

async function freshPostgres(handoff) {
  const project = handoff.isolation.project;
  const output = await command('docker', ['ps', '-aq', '--filter',
    `label=com.docker.compose.project=${project}`, '--filter',
    'label=com.docker.compose.service=postgres'], '');
  const ids = output.split(/\s+/).filter(Boolean);
  assert.equal(ids.length, 1, 'FRESH_POSTGRES_COUNT');
  const instances = JSON.parse(await command('docker', ['inspect', ids[0]], ''));
  assert.equal(instances.length, 1);
  assert.ok(instances[0].State.Running &&
    Date.parse(instances[0].Created) >= Date.parse(handoff.startedAt) &&
    instances[0].Mounts.some(mount => mount.Type === 'volume' &&
      mount.Name === `${project}_postgres-data`), 'FRESH_POSTGRES_IDENTITY');
  return ids[0];
}

async function restrictedQuery(container, database, role, password, sql) {
  assert.ok(['project_app', 'tenant_access_app'].includes(role));
  assert.ok(['project_db', 'tenant_access_db'].includes(database));
  const script = 'IFS= read -r PGPASSWORD; export PGPASSWORD; exec psql -qAt -v ON_ERROR_STOP=1 --username "$1" --dbname "$2" -c "$3"';
  const output = await command('docker', ['exec', '-i', container, 'sh', '-c', script,
    'psql', role, database, `BEGIN READ ONLY; ${sql}; COMMIT`], `${password}\n`);
  const objects = output.split('\n').filter(line => line.startsWith('{'));
  assert.equal(objects.length, 1, 'RESTRICTED_QUERY_RESULT');
  return JSON.parse(objects[0]);
}

function tenantSql(tenantId, select) {
  requireUuid(tenantId);
  return `SELECT set_config('app.tenant_id','${tenantId}',true); ${select}`;
}

export function assertRlsIsolation(empty, first, second, ids) {
  assert.deepEqual(empty, { projects: [], tasks: [] }, 'RLS_DEFAULT_DENY');
  assert.deepEqual(first, { projects: [ids.projectA], tasks: [ids.taskA] }, 'RLS_TENANT_A_ONLY');
  assert.deepEqual(second, { projects: [ids.projectB], tasks: [ids.taskB] }, 'RLS_TENANT_B_ONLY');
}

function curlConfig(token) {
  assert.match(token, /^[A-Za-z0-9._~-]+$/);
  return `header = "Authorization: Bearer ${token}"\n`;
}

export function requireTenantToken(token, tenantId, membershipId = null) {
  const pieces = token.split('.');
  assert.equal(pieces.length, 3, 'USER_TOKEN_FORMAT');
  const claims = JSON.parse(Buffer.from(pieces[1], 'base64url').toString('utf8'));
  assert.equal(claims.tenantId, tenantId, 'TOKEN_TENANT_MISMATCH');
  if (membershipId) assert.equal(claims.membershipId, membershipId, 'TOKEN_MEMBERSHIP_MISMATCH');
  assert.ok(Number.isInteger(claims.exp) && claims.exp > Date.now() / 1000,
    'USER_TOKEN_EXPIRED');
  return token;
}

function transport(handoff) {
  const edge = handoff.edge;
  if (edge) assert.ok(edge.host === '127.0.0.1' && Number.isInteger(edge.port) &&
    edge.port > 0 && edge.port <= 65535, 'EDGE_INVALID');
  return async (token, method, path, { body, version, key } = {}) => {
    assert.ok(path.startsWith('/api/v1/') && !path.includes('..'));
    const url = `${handoff.apiOrigin}${path}`;
    const args = ['--config', '-', '--silent', '--show-error', '--max-time', '20',
      '--proto', '=https', '--max-redirs', '0', '--request', method,
      '--write-out', '\n__SF_HTTP_STATUS__:%{http_code}', '--url', url];
    if (edge) args.push('--connect-to', `${new URL(url).hostname}:443:127.0.0.1:${edge.port}`);
    if (body !== undefined) args.push('--header', 'Content-Type: application/json',
      '--data-raw', JSON.stringify(body));
    if (version !== undefined) args.push('--header', `If-Match: "${version}"`);
    if (key) args.push('--header', `Idempotency-Key: ${key}`);
    const output = await command('curl', args, curlConfig(token), 25000);
    const marker = '\n__SF_HTTP_STATUS__:';
    const position = output.lastIndexOf(marker);
    assert.ok(position >= 0, 'HTTP_STATUS_MISSING');
    const status = Number(output.slice(position + marker.length));
    assert.ok(Number.isInteger(status) && status >= 100 && status <= 599);
    const raw = output.slice(0, position).trim();
    return { status, body: raw ? JSON.parse(raw) : null };
  };
}

async function assertGatewayIdentity(handoff) {
  const url = `${handoff.apiOrigin}/.well-known/jwks.json`;
  const args = ['--silent', '--show-error', '--fail', '--max-time', '15',
    '--proto', '=https', '--url', url];
  if (handoff.edge) args.push('--connect-to',
    `${new URL(url).hostname}:443:127.0.0.1:${handoff.edge.port}`);
  const { keys } = JSON.parse(await command('curl', args, '', 20000));
  assert.ok(Array.isArray(keys) && keys.length > 0 &&
    keys.every(key => key.kid && key.n && key.e && !key.d && !key.k), 'JWKS_INVALID');
  const actual = digest(JSON.stringify(keys.map(key => ({ kid: key.kid, n: key.n, e: key.e }))
    .sort((a, b) => a.kid.localeCompare(b.kid))));
  assert.equal(actual, handoff.jwksSha256, 'GATEWAY_ENVIRONMENT_CHANGED');
}

function expectStatus(response, status) {
  assert.equal(response.status, status, `HTTP_EXPECTED_${status}_GOT_${response.status}`);
  return response.body;
}

function expectForeign(response) {
  assert.equal(response.status, 404, `FOREIGN_RESOURCE_EXPOSED_${response.status}`);
}

export async function tenantReadIsolation(api, scenario, tokens) {
  const pairs = [
    [scenario.tenantA, scenario.tenantB, tokens.tenantA],
    [scenario.tenantB, scenario.tenantA, tokens.tenantB]
  ];
  for (const [own, foreign, token] of pairs) {
    const units = expectStatus(await api(token, 'GET', '/api/v1/organization/units'), 200);
    assert.equal(units.organizationId, own.tenantId);
    assert.ok(units.items.some(item => item.id === own.organizationUnitId));
    assert.ok(!units.items.some(item => item.id === foreign.organizationUnitId),
      'FOREIGN_ORGANIZATION_VISIBLE');
    const roles = expectStatus(await api(token, 'GET', '/api/v1/tenant-roles'), 200);
    assert.ok(roles.items.some(item => item.id === own.roleId));
    assert.ok(!roles.items.some(item => item.id === foreign.roleId), 'FOREIGN_ROLE_VISIBLE');
    const members = expectStatus(await api(token, 'GET', '/api/v1/members'), 200);
    assert.ok(members.items.some(item => item.membershipId === own.membershipId));
    assert.ok(!members.items.some(item => item.membershipId === foreign.membershipId),
      'FOREIGN_MEMBER_VISIBLE');
    const brand = expectStatus(await api(token, 'GET', '/api/v1/brand-profile'), 200);
    assert.equal(brand.profile?.displayName, own.brandDisplayName, 'BRAND_TENANT_MISMATCH');
    expectForeign(await api(token, 'GET', `/api/v1/tenant-roles/${foreign.roleId}`));
    expectForeign(await api(token, 'GET',
      `/api/v1/members/${foreign.membershipId}/roles`));
    expectForeign(await api(token, 'GET',
      `/api/v1/members/${foreign.membershipId}/organization-units`));
  }
}

export async function projectCrud(api, tokens, runId, db) {
  const a = tokens.tenantA, b = tokens.tenantB;
  const name = `authority-${runId.slice(0, 8)}`;
  const projectA = expectStatus(await api(a, 'POST', '/api/v1/projects',
    { body: { name }, key: uuidV7() }), 201);
  requireUuid(projectA.id);
  assert.ok(expectStatus(await api(a, 'GET', '/api/v1/projects'), 200).items
    .some(item => item.id === projectA.id), 'PROJECT_LIST_MISSING');
  expectStatus(await api(a, 'GET', `/api/v1/projects/${projectA.id}`), 200);
  const updatedProject = expectStatus(await api(a, 'PUT', `/api/v1/projects/${projectA.id}`,
    { body: { name: `${name}-updated` }, version: projectA.version, key: uuidV7() }), 200);
  assert.ok(updatedProject.version > projectA.version);

  expectForeign(await api(b, 'GET', `/api/v1/projects/${projectA.id}`));
  expectForeign(await api(b, 'PUT', `/api/v1/projects/${projectA.id}`,
    { body: { name: `${name}-foreign` }, version: updatedProject.version, key: uuidV7() }));
  expectForeign(await api(b, 'DELETE', `/api/v1/projects/${projectA.id}`,
    { version: updatedProject.version, key: uuidV7() }));
  expectForeign(await api(b, 'POST', `/api/v1/projects/${projectA.id}/tasks`,
    { body: { title: `${name}-foreign` }, key: uuidV7() }));

  const taskA = expectStatus(await api(a, 'POST', `/api/v1/projects/${projectA.id}/tasks`,
    { body: { title: `${name}-task` }, key: uuidV7() }), 201);
  requireUuid(taskA.id);
  assert.ok(expectStatus(await api(a, 'GET', `/api/v1/projects/${projectA.id}/tasks`), 200).items
    .some(item => item.id === taskA.id), 'TASK_LIST_MISSING');
  expectStatus(await api(a, 'GET', `/api/v1/projects/${projectA.id}/tasks/${taskA.id}`), 200);
  const updatedTask = expectStatus(await api(a, 'PUT',
    `/api/v1/projects/${projectA.id}/tasks/${taskA.id}`,
    { body: { title: `${name}-task-updated`, status: 'IN_PROGRESS' },
      version: taskA.version, key: uuidV7() }), 200);
  assert.ok(updatedTask.version > taskA.version);
  expectForeign(await api(b, 'GET', `/api/v1/projects/${projectA.id}/tasks/${taskA.id}`));
  expectForeign(await api(b, 'PUT', `/api/v1/projects/${projectA.id}/tasks/${taskA.id}`,
    { body: { title: 'foreign', status: 'TODO' }, version: updatedTask.version, key: uuidV7() }));
  expectForeign(await api(b, 'DELETE', `/api/v1/projects/${projectA.id}/tasks/${taskA.id}`,
    { version: updatedTask.version, key: uuidV7() }));

  const projectB = expectStatus(await api(b, 'POST', '/api/v1/projects',
    { body: { name: `${name}-b` }, key: uuidV7() }), 201);
  const taskB = expectStatus(await api(b, 'POST', `/api/v1/projects/${projectB.id}/tasks`,
    { body: { title: `${name}-task-b` }, key: uuidV7() }), 201);
  for (const value of [projectB.id, taskB.id]) requireUuid(value);
  assert.ok(!expectStatus(await api(b, 'GET', '/api/v1/projects'), 200).items
    .some(item => item.id === projectA.id), 'FOREIGN_PROJECT_LISTED');

  const ids = { projectA: projectA.id, taskA: taskA.id, projectB: projectB.id, taskB: taskB.id };
  await db(ids);

  expectStatus(await api(a, 'DELETE', `/api/v1/projects/${projectA.id}/tasks/${taskA.id}`,
    { version: updatedTask.version, key: uuidV7() }), 204);
  expectStatus(await api(a, 'DELETE', `/api/v1/projects/${projectA.id}`,
    { version: updatedProject.version, key: uuidV7() }), 204);
  expectStatus(await api(b, 'DELETE', `/api/v1/projects/${projectB.id}/tasks/${taskB.id}`,
    { version: taskB.version, key: uuidV7() }), 204);
  expectStatus(await api(b, 'DELETE', `/api/v1/projects/${projectB.id}`,
    { version: projectB.version, key: uuidV7() }), 204);
  return ids;
}

export function assertAdministratorRace(responses, before, after, staleStatus, survivorStatus, selfStatus) {
  assert.equal(before, 2, 'ADMIN_RACE_PRECONDITION');
  assert.deepEqual(responses.map(result => result.status).sort((a, b) => a - b), [200, 403],
    'ADMIN_RACE_RESULT');
  assert.equal(after, 1, 'LAST_ADMIN_LOST');
  assert.equal(staleStatus, 403, 'REVOKED_TOKEN_STILL_AUTHORIZED');
  assert.equal(survivorStatus, 200, 'SURVIVING_ADMIN_NOT_AUTHORIZED');
  assert.equal(selfStatus, 409, 'LAST_ADMIN_SELF_REVOCATION_ALLOWED');
}

export function assertAdminAssignment(assignments, roleId) {
  assert.ok(assignments?.items?.some(item => item.roleId === roleId &&
    item.roleKey === 'TENANT_ADMINISTRATOR' && item.systemManaged === true),
  'ADMIN_ROLE_PRECONDITION');
}

async function administratorRace(api, scenario, countAdministrators) {
  const race = scenario.lastAdministrators;
  const first = requireTenantToken(secret(race.first.tokenFile), race.tenantId,
    race.first.membershipId);
  const second = requireTenantToken(secret(race.second.tokenFile), race.tenantId,
    race.second.membershipId);
  const list = id => `/api/v1/members/${id}/roles`;
  for (const [token, member] of [[first, race.first], [second, race.second]]) {
    const assignments = expectStatus(await api(token, 'GET', list(member.membershipId)), 200);
    assertAdminAssignment(assignments, race.administratorRoleId);
  }
  const before = await countAdministrators(race.tenantId);
  assert.equal(before, 2, 'ADMIN_RACE_PRECONDITION');
  const responses = await Promise.all([
    api(first, 'DELETE', `${list(race.second.membershipId)}/${race.administratorRoleId}`),
    api(second, 'DELETE', `${list(race.first.membershipId)}/${race.administratorRoleId}`)
  ]);
  const after = await countAdministrators(race.tenantId);
  const winner = responses[0].status === 200 ? race.first : race.second;
  const stale = responses[0].status === 200 ? race.second : race.first;
  const staleStatus = (await api(stale === race.first ? first : second,
    'GET', list(stale.membershipId))).status;
  const survivingToken = winner === race.first ? first : second;
  const survivorStatus = (await api(survivingToken, 'GET', list(winner.membershipId))).status;
  const selfStatus = (await api(survivingToken, 'DELETE',
    `${list(winner.membershipId)}/${race.administratorRoleId}`)).status;
  assertAdministratorRace(responses, before, after, staleStatus, survivorStatus, selfStatus);
  assert.equal(await countAdministrators(race.tenantId), 1, 'LAST_ADMIN_CHANGED');
}

async function run(handoffPath, scenarioPath, outputPath) {
  const handoffBytes = privateFile(handoffPath), scenarioBytes = privateFile(scenarioPath);
  const handoff = JSON.parse(handoffBytes), scenario = JSON.parse(scenarioBytes);
  validateScenario(handoff, scenario);
  const projectPassword = secret(scenario.projectAppPasswordFile);
  const tenantPassword = secret(scenario.tenantAccessAppPasswordFile);
  const tokens = {
    tenantA: requireTenantToken(secret(scenario.tenantA.tokenFile), scenario.tenantA.tenantId),
    tenantB: requireTenantToken(secret(scenario.tenantB.tokenFile), scenario.tenantB.tenantId)
  };
  const postgres = await freshPostgres(handoff);
  await assertGatewayIdentity(handoff);
  const api = transport(handoff);
  const report = { schemaVersion: 1, kind: 'stage4-authority-probe',
    scope: 'project-crud-rls-admin-race-revocation', runId: handoff.runId,
    handoffSha256: digest(handoffBytes), scenarioSha256: digest(scenarioBytes),
    startedAt: new Date().toISOString(), status: 'failed', checks: [] };
  try {
    await tenantReadIsolation(api, scenario, tokens);
    report.checks.push({ name: 'organization-role-member-brand-two-tenant-isolation', status: 'passed' });
    const rls = async ids => {
      const select = `SELECT json_build_object('projects',
        (SELECT COALESCE(json_agg(id ORDER BY id),'[]'::json) FROM projects
          WHERE id IN ('${ids.projectA}','${ids.projectB}')),
        'tasks',(SELECT COALESCE(json_agg(id ORDER BY id),'[]'::json) FROM tasks
          WHERE id IN ('${ids.taskA}','${ids.taskB}')))`;
      const read = sql => restrictedQuery(postgres, 'project_db', 'project_app', projectPassword, sql);
      const empty = await read(select);
      const first = await read(tenantSql(scenario.tenantA.tenantId, select));
      const second = await read(tenantSql(scenario.tenantB.tenantId, select));
      assertRlsIsolation(empty, first, second, ids);
      report.checks.push({ name: 'project-app-rls-two-tenants', status: 'passed' });
    };
    const ids = await projectCrud(api, tokens, handoff.runId, rls);
    report.checks.push({ name: 'project-task-ten-crud-and-foreign-denials', status: 'passed',
      resourceSha256: digest(`${handoff.runId}:${ids.projectA}:${ids.projectB}`) });
    const countAdministrators = async tenantId => {
      const sql = tenantSql(tenantId, `SELECT json_build_object('count',count(DISTINCT m.id))
        FROM memberships m JOIN membership_role_assignments a
          ON a.tenant_id=m.tenant_id AND a.membership_id=m.id
        JOIN tenant_roles r ON r.tenant_id=a.tenant_id AND r.id=a.role_id
        WHERE m.tenant_id='${tenantId}' AND m.membership_status='ENABLED'
          AND r.role_key='TENANT_ADMINISTRATOR' AND r.system_managed
          AND NOT EXISTS (SELECT 1 FROM membership_disable_workflows work
            WHERE work.tenant_id=m.tenant_id AND work.membership_id=m.id
              AND work.workflow_status IN ('REVOKING','RECOVERY_REQUIRED','RELEASE_PENDING'))
          AND NOT EXISTS (SELECT 1 FROM membership_enable_workflows work
            WHERE work.tenant_id=m.tenant_id AND work.membership_id=m.id
              AND work.workflow_status IN ('CONSUMING','COMPENSATION_PENDING','FENCE_PENDING'))`);
      const result = await restrictedQuery(postgres, 'tenant_access_db', 'tenant_access_app', tenantPassword, sql);
      return result.count;
    };
    await administratorRace(api, scenario, countAdministrators);
    report.checks.push({ name: 'last-admin-concurrency-and-stale-token-rejection', status: 'passed' });
    report.status = 'passed';
  } catch (error) {
    report.reason = /^[A-Z0-9_]{3,90}$/.test(error.message)
      ? error.message : 'AUTHORITY_PROBE_REJECTED';
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
  } catch {
    console.error('STAGE4_AUTHORITY_PROBE_FAILED: check private inputs and this Fresh project');
    process.exitCode = 1;
  }
}
