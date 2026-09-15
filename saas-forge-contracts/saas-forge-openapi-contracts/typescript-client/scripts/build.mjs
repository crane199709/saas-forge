import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFile, readFile, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const repository = fileURLToPath(new URL('../../../../', import.meta.url));
const contract = 'saas-forge-contracts/saas-forge-openapi-contracts/';
const inputs = [
  `${contract}v1.yaml`, `${contract}common.yaml`, `${contract}pom.xml`, 'pom.xml', 'LICENSE',
  ...['package.json', 'package-lock.json', 'tsconfig.json', 'scripts/build.mjs', 'scripts/check-release.mjs']
    .map(path => `${contract}typescript-client/${path}`),
];
const run = (command, args, cwd = repository) => execFileSync(command, args, { cwd, stdio: 'inherit' });
const git = (...args) => execFileSync('git', args, { cwd: repository, encoding: 'utf8' }).trim();
const hashes = async () => Object.fromEntries(await Promise.all(inputs.map(async path => [
  path, createHash('sha256').update(await readFile(`${repository}/${path}`)).digest('hex'),
])));

const before = await hashes();
await rm(`${packageRoot}/target`, { recursive: true, force: true });
await rm(`${packageRoot}/dist`, { recursive: true, force: true });
run(`${repository}/mvnw`, ['--batch-mode', '--no-transfer-progress', '-pl',
  'saas-forge-contracts/saas-forge-openapi-contracts', '-am',
  `-Dtypescript.client.output=${packageRoot}/target/generated`,
  '-Pstandalone-typescript-client', 'generate-sources']);
run(process.execPath, [`${packageRoot}/node_modules/typescript/bin/tsc`, '--project', 'tsconfig.json'], packageRoot);
if (JSON.stringify(before) !== JSON.stringify(await hashes())) throw new Error('Contract changed during build');
const manifest = JSON.parse(await readFile(`${packageRoot}/package.json`, 'utf8'));
await writeFile(`${packageRoot}/contract-source.json`, JSON.stringify({
  package: manifest.name,
  version: manifest.version,
  repository: 'https://github.com/crane0927/saas-forge',
  commit: git('rev-parse', 'HEAD'),
  dirty: git('status', '--porcelain', '--untracked-files=normal', '--', ...inputs, `${contract}typescript-client`) !== '',
  contract: `${contract}v1.yaml`,
  sha256: before,
  generator: { name: 'typescript-fetch', version: (await readFile(`${packageRoot}/target/generated/.openapi-generator/VERSION`, 'utf8')).trim(), importFileExtension: '.js' },
}, null, 2) + '\n');
await copyFile(`${repository}/LICENSE`, `${packageRoot}/LICENSE`);
