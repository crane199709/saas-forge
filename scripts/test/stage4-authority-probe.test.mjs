import assert from 'node:assert/strict';
import test from 'node:test';
import { assertAdminAssignment, assertAdministratorRace, assertRlsIsolation, projectCrud, uuidV7,
  requireTenantToken, tenantReadIsolation, validateScenario } from '../stage4-authority-probe.mjs';

const id = suffix => `0199d1b0-1111-7000-8000-${String(suffix).padStart(12, '0')}`;
const now = new Date();
const handoff = {
  schemaVersion: 1, status: 'ready', runId: id(1), apiOrigin: 'https://api.saas.forge.test',
  readyAt: new Date(now.getTime() - 60000).toISOString(),
  expiresAt: new Date(now.getTime() + 60000).toISOString(),
  requiredServices: ['project-service'],
  jwksSha256: 'a'.repeat(64),
  isolation: { kind: 'fresh-compose', project: `sf-acceptance-${id(1)}` }
};
const scenario = {
  runId: handoff.runId,
  tenantA: { tenantId: id(2), tokenFile: '/private/a', organizationUnitId: id(20),
    roleId: id(21), membershipId: id(22), brandDisplayName: 'Brand A' },
  tenantB: { tenantId: id(3), tokenFile: '/private/b', organizationUnitId: id(30),
    roleId: id(31), membershipId: id(32), brandDisplayName: 'Brand B' },
  lastAdministrators: {
    tenantId: id(2), administratorRoleId: id(4),
    first: { membershipId: id(5), tokenFile: '/private/a' },
    second: { membershipId: id(6), tokenFile: '/private/second' }
  },
  projectAppPasswordFile: '/private/project-password',
  tenantAccessAppPasswordFile: '/private/tenant-password'
};

test('accepts only a same-round fresh project and two distinct tenants', () => {
  assert.doesNotThrow(() => validateScenario(handoff, scenario));
  assert.throws(() => validateScenario({ ...handoff, isolation: { kind: 'existing-environment' } }, scenario));
  assert.throws(() => validateScenario(handoff,
    { ...scenario, tenantB: { ...scenario.tenantB, tenantId: scenario.tenantA.tenantId } }));
  assert.match(uuidV7(1760000000000, Buffer.alloc(10)), /-7[0-9a-f]{3}-[89ab]/);
});

test('token preflight binds a real Tenant context before any mutation', () => {
  const payload = { tenantId: scenario.tenantA.tenantId,
    membershipId: scenario.tenantA.membershipId, exp: Math.floor(Date.now() / 1000) + 60 };
  const token = `header.${Buffer.from(JSON.stringify(payload)).toString('base64url')}.signature`;
  assert.equal(requireTenantToken(token, scenario.tenantA.tenantId,
    scenario.tenantA.membershipId), token);
  assert.throws(() => requireTenantToken(token, scenario.tenantB.tenantId));
  assert.throws(() => requireTenantToken(token, scenario.tenantA.tenantId,
    scenario.tenantB.membershipId));
});

test('live read checks compare organization, role, member and brand sentinels in both directions', async () => {
  const responses = [];
  for (const tenant of [scenario.tenantA, scenario.tenantB]) {
    responses.push({ status: 200, body: { organizationId: tenant.tenantId,
      items: [{ id: tenant.organizationUnitId }] } });
    responses.push({ status: 200, body: { items: [{ id: tenant.roleId }] } });
    responses.push({ status: 200, body: { items: [{ membershipId: tenant.membershipId }] } });
    responses.push({ status: 200, body: { profile: { displayName: tenant.brandDisplayName } } });
    responses.push({ status: 404 }, { status: 404 }, { status: 404 });
  }
  const paths = ['/api/v1/organization/units', '/api/v1/tenant-roles',
    '/api/v1/members', '/api/v1/brand-profile',
    `/api/v1/tenant-roles/${scenario.tenantB.roleId}`,
    `/api/v1/members/${scenario.tenantB.membershipId}/roles`,
    `/api/v1/members/${scenario.tenantB.membershipId}/organization-units`,
    '/api/v1/organization/units', '/api/v1/tenant-roles',
    '/api/v1/members', '/api/v1/brand-profile',
    `/api/v1/tenant-roles/${scenario.tenantA.roleId}`,
    `/api/v1/members/${scenario.tenantA.membershipId}/roles`,
    `/api/v1/members/${scenario.tenantA.membershipId}/organization-units`];
  let index = 0;
  await tenantReadIsolation(async (token, method, path) => {
    assert.equal(token, index < 7 ? 'a' : 'b');
    assert.equal(method, 'GET');
    assert.equal(path, paths[index]);
    return responses[index++];
  }, scenario, { tenantA: 'a', tenantB: 'b' });
  assert.equal(index, 14);
  responses[7].body.items.push({ id: scenario.tenantA.organizationUnitId });
  index = 0;
  await assert.rejects(tenantReadIsolation(async () => responses[index++],
    scenario, { tenantA: 'a', tenantB: 'b' }));
});

test('restricted Project role must see no rows without context and only its own Tenant rows', () => {
  const ids = { projectA: id(10), projectB: id(11), taskA: id(12), taskB: id(13) };
  assertRlsIsolation({ projects: [], tasks: [] },
    { projects: [ids.projectA], tasks: [ids.taskA] },
    { projects: [ids.projectB], tasks: [ids.taskB] }, ids);
  assert.throws(() => assertRlsIsolation({ projects: [ids.projectA], tasks: [] },
    { projects: [ids.projectA], tasks: [ids.taskA] },
    { projects: [ids.projectB], tasks: [ids.taskB] }, ids));
  assert.throws(() => assertRlsIsolation({ projects: [], tasks: [] },
    { projects: [ids.projectA, ids.projectB], tasks: [ids.taskA] },
    { projects: [ids.projectB], tasks: [ids.taskB] }, ids));
});

test('last administrator race requires one committed revoke and immediate stale-token rejection', () => {
  assertAdminAssignment({ items: [{ roleId: scenario.lastAdministrators.administratorRoleId,
    roleKey: 'TENANT_ADMINISTRATOR', systemManaged: true }] },
  scenario.lastAdministrators.administratorRoleId);
  assert.throws(() => assertAdminAssignment({ items: [{ roleId: id(99),
    roleKey: 'CUSTOM', systemManaged: false }] }, scenario.lastAdministrators.administratorRoleId));
  assertAdministratorRace([{ status: 403 }, { status: 200 }], 2, 1, 403, 200, 409);
  assert.throws(() => assertAdministratorRace([{ status: 200 }, { status: 200 }], 2, 0, 403, 200, 409));
  assert.throws(() => assertAdministratorRace([{ status: 200 }, { status: 403 }], 2, 1, 200, 200, 409));
  assert.throws(() => assertAdministratorRace([{ status: 200 }, { status: 403 }], 2, 1, 403, 200, 200));
});

test('Project and Task ten CRUD calls use real route shapes and reject foreign resources', async () => {
  const projectA = { id: id(10), version: 1 }, projectB = { id: id(11), version: 1 };
  const taskA = { id: id(12), version: 1 }, taskB = { id: id(13), version: 1 };
  const pA = `/api/v1/projects/${projectA.id}`, pB = `/api/v1/projects/${projectB.id}`;
  const tA = `${pA}/tasks/${taskA.id}`, tB = `${pB}/tasks/${taskB.id}`;
  const calls = [
    ['a', 'POST', '/api/v1/projects', 201, projectA],
    ['a', 'GET', '/api/v1/projects', 200, { items: [projectA] }],
    ['a', 'GET', pA, 200, projectA],
    ['a', 'PUT', pA, 200, { ...projectA, version: 2 }],
    ['b', 'GET', pA, 404], ['b', 'PUT', pA, 404], ['b', 'DELETE', pA, 404],
    ['b', 'POST', `${pA}/tasks`, 404],
    ['a', 'POST', `${pA}/tasks`, 201, taskA],
    ['a', 'GET', `${pA}/tasks`, 200, { items: [taskA] }],
    ['a', 'GET', tA, 200, taskA],
    ['a', 'PUT', tA, 200, { ...taskA, version: 2 }],
    ['b', 'GET', tA, 404], ['b', 'PUT', tA, 404], ['b', 'DELETE', tA, 404],
    ['b', 'POST', '/api/v1/projects', 201, projectB],
    ['b', 'POST', `${pB}/tasks`, 201, taskB],
    ['b', 'GET', '/api/v1/projects', 200, { items: [projectB] }],
    ['a', 'DELETE', tA, 204], ['a', 'DELETE', pA, 204],
    ['b', 'DELETE', tB, 204], ['b', 'DELETE', pB, 204]
  ];
  let rlsChecked = false;
  await projectCrud(async (token, method, path, options) => {
    const [expectedToken, expectedMethod, expectedPath, status, body = null] = calls.shift();
    assert.deepEqual([token, method, path], [expectedToken, expectedMethod, expectedPath]);
    if (['POST', 'PUT', 'DELETE'].includes(method)) assert.match(options.key, /-7[0-9a-f]{3}-/);
    return { status, body };
  }, { tenantA: 'a', tenantB: 'b' }, handoff.runId, async ids => {
    assert.deepEqual(ids, { projectA: projectA.id, taskA: taskA.id,
      projectB: projectB.id, taskB: taskB.id });
    rlsChecked = true;
  });
  assert.equal(calls.length, 0);
  assert.equal(rlsChecked, true);
});
