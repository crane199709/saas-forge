import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const cli = new URL('../acceptance-handoff.mjs', import.meta.url).pathname;

test('prepare reserves a new round without starting a process or reusing an earlier receipt', () => {
  const root = mkdtempSync(join(tmpdir(), 'handoff-test-'));
  try {
    const directory = join(root, 'round');
    const args = [cli, 'prepare', directory, 'https://console.example.test', 'https://api.example.test'];
    const result = spawnSync(process.execPath, args, { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    const receipt = JSON.parse(readFileSync(join(directory, 'preparation.json'), 'utf8'));
    assert.match(receipt.runId, /^[0-9a-f-]{36}$/);
    assert.equal(receipt.status, 'prepared');
    assert.equal(receipt.isolation.kind, 'fresh-compose');
    assert.equal(receipt.isolation.project, `sf-acceptance-${receipt.runId}`);
    assert.equal(receipt.cleanup.owner, 'environment-preparer');
    assert.equal(receipt.backend.commit.length, 40);
    assert.equal(spawnSync(process.execPath, args).status, 1);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('invalid targets fail without writing a handoff or disclosing the supplied value', () => {
  const root = mkdtempSync(join(tmpdir(), 'handoff-test-'));
  try {
    const result = spawnSync(process.execPath, [cli, 'prepare', join(root, 'round'),
      'https://user:private-secret@console.example.test', 'https://api.example.test'], { encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.ok(!`${result.stdout}${result.stderr}`.includes('private-secret'));
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('prepared round cannot be used as ready browser evidence', () => {
  const root = mkdtempSync(join(tmpdir(), 'handoff-test-'));
  try {
    const directory = join(root, 'round');
    spawnSync(process.execPath, [cli, 'prepare', directory, 'https://console.example.test', 'https://api.example.test']);
    const result = spawnSync(process.execPath, [cli, 'correlate', join(directory, 'preparation.json'),
      join(root, 'backend.json'), join(root, 'browser.json'), join(root, 'result.json')]);
    assert.equal(result.status, 1);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

function readyReceipt() {
  return { schemaVersion: 1, runId: '75a812cc-bf14-610c-bf4f-169d15ed648e', status: 'ready',
    readyAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 3600000).toISOString(),
    consoleOrigin: 'https://console.example.test', apiOrigin: 'https://api.example.test',
    backend: { commit: 'a'.repeat(40), dirty: false, versionSource: 'source-checkout' },
    isolation: { kind: 'existing-environment' }, cleanup: { owner: 'environment-preparer', automatic: false } };
}

test('correlate joins only successful evidence from the exact same handoff and round', () => {
  const root = mkdtempSync(join(tmpdir(), 'handoff-test-'));
  try {
    const receipt = readyReceipt();
    const body = JSON.stringify(receipt);
    writeFileSync(join(root, 'handoff.json'), body);
    const sha = createHash('sha256').update(body).digest('hex');
    const report = { schemaVersion: 1, runId: receipt.runId, handoffSha256: sha,
      startedAt: receipt.readyAt, finishedAt: new Date().toISOString(), status: 'passed',
      checks: [{ name: 'login', status: 'passed' }] };
    writeFileSync(join(root, 'backend.json'), JSON.stringify({ ...report, kind: 'backend-probe' }));
    writeFileSync(join(root, 'browser.json'), JSON.stringify({ ...report, kind: 'browser' }));
    const args = [cli, 'correlate', ...['handoff.json', 'backend.json', 'browser.json', 'result.json'].map(p => join(root, p))];
    const valid = spawnSync(process.execPath, args, { encoding: 'utf8' });
    assert.equal(valid.status, 0, valid.stderr);
    assert.equal(JSON.parse(readFileSync(join(root, 'result.json'))).status, 'passed');
    rmSync(join(root, 'result.json'));
    for (const invalid of [
      { runId: 'another-round' }, { handoffSha256: 'b'.repeat(64) }, { status: 'failed' },
      { checks: [{ name: 'login', status: 'skipped' }] }, { checks: [] },
      { startedAt: '2000-01-01T00:00:00Z' }, { finishedAt: 'not-a-date' }
    ]) {
      writeFileSync(join(root, 'browser.json'), JSON.stringify({ ...report, kind: 'browser', ...invalid }));
      assert.equal(spawnSync(process.execPath, args).status, 1);
    }
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('ready verifies fresh resource ownership and rejects containers attached to another environment', () => {
  const root = mkdtempSync(join(tmpdir(), 'handoff-test-'));
  try {
    const directory = join(root, 'round');
    spawnSync(process.execPath, [cli, 'prepare', directory, 'https://console.example.test', 'https://api.example.test']);
    const receipt = JSON.parse(readFileSync(join(directory, 'preparation.json')));
    const project = receipt.isolation.project;
    const created = new Date().toISOString();
    const services = ['gateway', 'iam-service', 'tenant-access-service', 'entitlement-service', 'audit-service', 'postgres', 'redis', 'kafka'];
    const containers = services.map(service => ({ Id: service, Image: `sha256:${'a'.repeat(64)}`,
      Created: created, Config: { Labels: { 'com.docker.compose.project': project, 'com.docker.compose.service': service } },
      State: { Running: true }, Mounts: ['postgres', 'redis', 'kafka'].includes(service) ? [{ Type: 'volume', Name: `${project}_${service}-data` }] : [], NetworkSettings: { Networks: { [`${project}_default`]: {} },
        Ports: { '8080/tcp': [{ HostIp: '127.0.0.1', HostPort: '12345' }] } } }));
    const fixturePath = join(root, 'fixture.json');
    writeFileSync(fixturePath, JSON.stringify(containers));
    writeFileSync(join(root, 'docker'), `#!${process.execPath}\nimport fs from 'node:fs';
const args = process.argv.slice(2); const project = ${JSON.stringify(project)};
if (args[0] === 'ps') console.log('gateway iam-service tenant-access-service entitlement-service audit-service postgres redis kafka');
else if (args[0] === 'inspect') console.log(fs.readFileSync(${JSON.stringify(fixturePath)}, 'utf8'));
else if (args[0] === 'image') console.log(JSON.stringify([{Config:{Labels:{'org.opencontainers.image.revision':${JSON.stringify(receipt.backend.commit)},'io.saasforge.source-dirty':${JSON.stringify(String(receipt.backend.dirty))}}}}]));
else if (args[0] === 'exec') console.error('openjdk version "17.0.1"');
else if (args[0] === 'volume') console.log(JSON.stringify(['postgres-data','redis-data','kafka-data'].map(name => ({Name:project+'_'+name,CreatedAt:${JSON.stringify(created)},Labels:{'com.docker.compose.project':project}}))));
else if (args[0] === 'network') console.log(JSON.stringify([{Name:project+'_default',Created:${JSON.stringify(created)},Labels:{'com.docker.compose.project':project}}]));
else process.exit(2);`, { mode: 0o700 });
    writeFileSync(join(root, 'curl'), `#!${process.execPath}\nconsole.log('{"keys":[{"kid":"round-key","n":"modulus","e":"AQAB"}]}');`, { mode: 0o700 });
    const run = () => spawnSync(process.execPath, [cli, 'ready', directory], {
      env: { ...process.env, PATH: `${root}:${process.env.PATH}` }, encoding: 'utf8' });
    const result = run();
    assert.equal(result.status, 0, result.stderr);
    const handoff = JSON.parse(readFileSync(join(directory, 'handoff.json')));
    assert.equal(handoff.isolation.volumes.length, 3);
    assert.equal(handoff.backend.images.length, 5);
    rmSync(join(directory, 'handoff.json'));
    containers.find(c => c.Id === 'postgres').Mounts = [];
    writeFileSync(fixturePath, JSON.stringify(containers));
    assert.equal(run().status, 1);
    containers.find(c => c.Id === 'postgres').Mounts = [{ Type: 'volume', Name: `${project}_postgres-data` }];
    containers[0].NetworkSettings.Networks = { shared_default: {} };
    writeFileSync(fixturePath, JSON.stringify(containers));
    assert.equal(run().status, 1);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
