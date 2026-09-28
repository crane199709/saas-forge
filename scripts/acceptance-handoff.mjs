import { randomUUID, createHash } from 'node:crypto';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawnSync } from 'node:child_process';

const repository = fileURLToPath(new URL('../', import.meta.url));
const git = (...args) => execFileSync('git', args, { cwd: repository, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
function origin(value) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.origin !== value || url.username || url.password) throw new Error('ORIGIN_INVALID');
  return value;
}
function save(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600, flag: 'wx' });
}
function prepare(directory, consoleOrigin, apiOrigin) {
  const runId = randomUUID();
  const receipt = {
    schemaVersion: 1,
    runId,
    status: 'prepared',
    startedAt: new Date().toISOString(),
    consoleOrigin: origin(consoleOrigin),
    apiOrigin: origin(apiOrigin),
    backend: { commit: git('rev-parse', 'HEAD'), dirty: Boolean(git('status', '--porcelain')), versionSource: 'source-checkout' },
    isolation: { kind: 'fresh-compose', project: `sf-acceptance-${runId}` },
    cleanup: { owner: 'environment-preparer', automatic: false }
  };
  mkdirSync(directory, { mode: 0o700 });
  save(resolve(directory, 'preparation.json'), receipt);
  const infrastructure = ['postgres', 'redis', 'kafka', 'mailpit', 'otel-collector', 'nacos'];
  const overlay = ['services:', ...infrastructure.flatMap(name => [`  ${name}:`, '    ports: !reset []']),
    ...services.flatMap(name => [`  ${name}:`, '    ports: !reset []', `    image: saas.forge/acceptance-${runId}/${name}:local`, '    build:', '      labels:',
      `        org.opencontainers.image.revision: "${receipt.backend.commit}"`,
      `        io.saasforge.source-dirty: "${receipt.backend.dirty}"`])];
  // Gateway 只在随机回环端口开放；受信 HTTPS Edge 由环境准备方另行配置。
  const gatewayIndex = overlay.indexOf('  gateway:');
  overlay[gatewayIndex + 1] = '    ports: !override ["127.0.0.1::8080"]';
  for (const name of ['iam-platform-admin-bootstrap', 'iam-platform-admin-credential-reset',
    'iam-reserved-service-client-bootstrap', 'iam-reserved-service-client-replacement']) {
    overlay.push(`  ${name}:`, `    image: saas.forge/acceptance-${runId}/iam-service:local`);
  }
  writeFileSync(resolve(directory, 'compose.override.yaml'), `${overlay.join('\n')}\n`, { mode: 0o600, flag: 'wx' });
  console.log(JSON.stringify({ runId, project: receipt.isolation.project, status: receipt.status }));
}

function read(path) { return JSON.parse(readFileSync(path, 'utf8')); }
function hash(path) { return createHash('sha256').update(readFileSync(path)).digest('hex'); }
function requireReady(receipt) {
  if (receipt.schemaVersion !== 1 || receipt.status !== 'ready' ||
      !/^[0-9a-f-]{36}$/.test(receipt.runId) || !Number.isFinite(Date.parse(receipt.readyAt)) ||
      !Number.isFinite(Date.parse(receipt.expiresAt))) throw new Error('HANDOFF_INVALID');
  origin(receipt.consoleOrigin);
  origin(receipt.apiOrigin);
}
function correlate(handoff, backend, browser, output) {
  const receipt = read(handoff);
  requireReady(receipt);
  const digest = hash(handoff);
  const reports = [read(backend), read(browser)];
  for (const [index, report] of reports.entries()) {
    if (report.schemaVersion !== 1 || report.kind !== ['backend-probe', 'browser'][index] ||
        report.runId !== receipt.runId || report.handoffSha256 !== digest ||
        !Number.isFinite(Date.parse(report.startedAt)) || !Number.isFinite(Date.parse(report.finishedAt)) ||
        Date.parse(report.startedAt) < Date.parse(receipt.readyAt) ||
        Date.parse(report.finishedAt) < Date.parse(report.startedAt) ||
        Date.parse(report.finishedAt) > Date.parse(receipt.expiresAt) ||
        report.status !== 'passed' || !Array.isArray(report.checks) || !report.checks.length ||
        report.checks.some(check => check.status !== 'passed')) throw new Error('EVIDENCE_MISMATCH');
  }
  // 只输出关联摘要，不复制可能由其他工具写入的额外字段。
  save(output, { schemaVersion: 1, runId: receipt.runId, handoffSha256: digest, status: 'passed',
    scope: 'handoff-smoke-only', backendSha256: hash(backend), browserSha256: hash(browser) });
}

const services = ['gateway', 'iam-service', 'tenant-access-service', 'entitlement-service', 'audit-service'];
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 }).trim();
function publicJwks(target) {
  const result = execFileSync('curl', ['--silent', '--show-error', '--fail', '--max-time', '15',
    '--proto', '=https,http', target + '/.well-known/jwks.json'],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 20000 });
  const { keys } = JSON.parse(result);
  if (!Array.isArray(keys) || !keys.length || keys.some(key => !key.kid || key.d || key.k)) throw new Error('JWKS_INVALID');
  return createHash('sha256').update(JSON.stringify(keys.map(key => ({ kid: key.kid, n: key.n, e: key.e }))
    .sort((a, b) => a.kid.localeCompare(b.kid)))).digest('hex');
}
function ready(directory) {
  const receipt = read(resolve(directory, 'preparation.json'));
  if (receipt.status !== 'prepared' || receipt.isolation.project !== `sf-acceptance-${receipt.runId}` ||
      receipt.backend.commit !== git('rev-parse', 'HEAD')) throw new Error('PREPARATION_CHANGED');
  const project = receipt.isolation.project;
  const ids = docker('ps', '-aq', '--filter', `label=com.docker.compose.project=${project}`).split(/\s+/).filter(Boolean);
  if (!ids.length) throw new Error('ENVIRONMENT_NOT_STARTED');
  const containers = JSON.parse(docker('inspect', ...ids));
  for (const container of containers) {
    if (container.Config.Labels['com.docker.compose.project'] !== project ||
        Object.keys(container.NetworkSettings.Networks).some(name => name !== `${project}_default`) ||
        container.Mounts.some(mount => mount.Type === 'volume' && !mount.Name.startsWith(`${project}_`))) {
      throw new Error('CROSS_ENVIRONMENT_RESOURCE');
    }
  }
  const images = [];
  for (const service of services) {
    const instances = containers.filter(c => c.Config.Labels['com.docker.compose.service'] === service &&
      c.Config.Labels['com.docker.compose.oneoff'] !== 'True');
    if (instances.length !== 1 || !instances[0].State.Running) throw new Error('SERVICE_NOT_READY');
    const instance = instances[0];
    if (Date.parse(instance.Created) < Date.parse(receipt.startedAt)) throw new Error('REUSED_CONTAINER');
    const image = JSON.parse(docker('image', 'inspect', instance.Image))[0];
    if (image.Config.Labels?.['org.opencontainers.image.revision'] !== receipt.backend.commit ||
        image.Config.Labels?.['io.saasforge.source-dirty'] !== String(receipt.backend.dirty)) throw new Error('IMAGE_VERSION_MISMATCH');
    const java = spawnSync('docker', ['exec', instance.Id, 'java', '-version'],
      { encoding: 'utf8', timeout: 15000 });
    if (java.status !== 0 || !/version "17[."]/.test(java.stdout + java.stderr)) throw new Error('JDK_NOT_17');
    images.push({ service, imageId: instance.Image, containerId: instance.Id });
  }
  for (const service of ['postgres', 'redis', 'kafka']) {
    const instances = containers.filter(c => c.Config.Labels['com.docker.compose.service'] === service);
    if (instances.length !== 1 || !instances[0].State.Running ||
        !instances[0].Mounts.some(m => m.Type === 'volume' && m.Name === `${project}_${service}-data`)) {
      throw new Error('DATA_VOLUME_NOT_ATTACHED');
    }
  }
  const volumes = ['postgres-data', 'redis-data', 'kafka-data'].map(name => `${project}_${name}`);
  for (const volume of JSON.parse(docker('volume', 'inspect', ...volumes))) {
    if (volume.Labels?.['com.docker.compose.project'] !== project ||
        Date.parse(volume.CreatedAt) < Date.parse(receipt.startedAt) - 1000) throw new Error('VOLUME_NOT_FRESH');
  }
  const network = JSON.parse(docker('network', 'inspect', `${project}_default`))[0];
  if (network.Labels?.['com.docker.compose.project'] !== project ||
      Date.parse(network.Created) < Date.parse(receipt.startedAt)) throw new Error('NETWORK_NOT_FRESH');
  const gateway = containers.find(c => c.Config.Labels['com.docker.compose.service'] === 'gateway');
  const ports = gateway.NetworkSettings.Ports['8080/tcp'];
  if (ports?.length !== 1 || ports[0].HostIp !== '127.0.0.1') throw new Error('GATEWAY_PORT_INVALID');
  const jwksSha256 = publicJwks(`http://127.0.0.1:${ports[0].HostPort}`);
  if (publicJwks(receipt.apiOrigin) !== jwksSha256) throw new Error('HTTPS_WRONG_ENVIRONMENT');
  save(resolve(directory, 'handoff.json'), { ...receipt, status: 'ready', readyAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 86400000).toISOString(),
    backend: { ...receipt.backend, images, jdkMajor: 17 },
    isolation: { ...receipt.isolation, volumes, network: network.Name }, jwksSha256 });
}

/** 普通已启动环境只作交接冒烟；绝不把源目录版本冒充运行中制品或 Fresh 证明。 */
function attach(directory, consoleOrigin, apiOrigin) {
  const receipt = {
    schemaVersion: 1, runId: randomUUID(), status: 'ready', readyAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 86400000).toISOString(), consoleOrigin: origin(consoleOrigin), apiOrigin: origin(apiOrigin),
    backend: { commit: git('rev-parse', 'HEAD'), dirty: Boolean(git('status', '--porcelain')),
      versionSource: 'source-checkout-only-runtime-unverified' },
    isolation: { kind: 'existing-environment' }, cleanup: { owner: 'environment-preparer', automatic: false },
    jwksSha256: publicJwks(apiOrigin)
  };
  mkdirSync(directory, { mode: 0o700 });
  save(resolve(directory, 'handoff.json'), receipt);
  console.log(JSON.stringify({ runId: receipt.runId, status: 'ready', isolation: receipt.isolation.kind }));
}
function probe(handoff, output) {
  const receipt = read(handoff);
  requireReady(receipt);
  if (Date.now() > Date.parse(receipt.expiresAt)) throw new Error('HANDOFF_EXPIRED');
  const report = { schemaVersion: 1, kind: 'backend-probe', runId: receipt.runId, handoffSha256: hash(handoff),
    startedAt: new Date().toISOString(), checks: [], status: 'failed' };
  try {
    if (publicJwks(receipt.apiOrigin) !== receipt.jwksSha256) throw new Error('ENVIRONMENT_CHANGED');
    report.checks.push({ name: 'gateway-public-key-environment', status: 'passed' });
    for (const [name, source, expected] of [
      ['console-origin-allowed', receipt.consoleOrigin, 200],
      ['untrusted-origin-denied', `https://untrusted.${new URL(receipt.consoleOrigin).hostname}`, 403]
    ]) {
      const headers = execFileSync('curl', ['--silent', '--show-error', '--max-time', '15',
        '--proto', '=https', '--dump-header', '-', '--output', '/dev/null', '--header', `Origin: ${source}`,
        `${receipt.apiOrigin}/.well-known/jwks.json`],
        { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 20000 });
      const statuses = [...headers.matchAll(/^HTTP\/[^ ]+ (\d+)/gm)];
      const allowed = /^access-control-allow-origin:\s*([^\r\n]+)/im.exec(headers)?.[1].trim();
      if (Number(statuses.at(-1)?.[1]) !== expected ||
          (expected === 200 ? allowed !== receipt.consoleOrigin : allowed !== undefined)) throw new Error('ORIGIN_PROBE_FAILED');
      report.checks.push({ name, status: 'passed' });
    }
    report.status = 'passed';
  } catch {
    report.checks.push({ name: 'gateway-probes', status: 'failed' });
    throw new Error('PROBE_FAILED');
  } finally {
    report.finishedAt = new Date().toISOString();
    save(output, report);
  }
}

try {
  const [command, ...args] = process.argv.slice(2);
  if (command === 'prepare' && args.length === 3) prepare(...args);
  else if (command === 'ready' && args.length === 1) ready(...args);
  else if (command === 'attach' && args.length === 3) attach(...args);
  else if (command === 'probe' && args.length === 2) probe(...args);
  else if (command === 'correlate' && args.length === 4) correlate(...args);
  else throw new Error('USAGE');
} catch {
  // 不回显参数或外部命令 stderr，避免个人配置和凭据进入报告。
  console.error('HANDOFF_FAILED: check command, trusted origins, files and environment; no automatic cleanup');
  process.exitCode = 1;
}
