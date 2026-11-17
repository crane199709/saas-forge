#!/usr/bin/env python3
"""用实际发布包构造固定旧工程，按已审阅差异升级并保留业务源码。"""
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request
from urllib.parse import urlparse


def run(*command, cwd):
    subprocess.run(command, cwd=cwd, check=True)


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def hashes(root):
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.rglob('*')) if p.is_file()}


def main():
    work = Path(sys.argv[1]).resolve()
    repo = Path(__file__).resolve().parents[2]
    if work == repo or repo in work.parents:
        raise SystemExit('升级样例必须位于仓库外')
    work.mkdir()  # 已存在即失败，保留前一次证据。
    published = Path(sys.argv[2]).resolve() if sys.argv[2] else work / 'published'
    published.mkdir(exist_ok=True)
    registry = os.environ['NPM_RELEASE_REGISTRY']
    if urlparse(registry).hostname not in ('127.0.0.1', 'localhost'):
        raise SystemExit('仅允许候选的本机临时 registry，不向目标仓库上传')
    candidate = Path(os.environ['FORGE_CANDIDATE_DIR']).resolve()
    candidate_record = json.loads((candidate / 'candidate.json').read_text())
    version = candidate_record['sourceVersion']
    provenance = []
    for name in ('sdk', 'ui', 'sub-app-kit'):
        metadata = published / f'{name}-metadata.json'
        if not metadata.exists():
            with urllib.request.urlopen(f'https://registry.npmjs.org/@crane199709/{name}/0.1.0') as response:
                metadata.write_bytes(response.read())
        package = json.loads(metadata.read_text())
        if package['name'] != f'@crane199709/{name}' or package['version'] != '0.1.0':
            raise SystemExit(f'旧版包身份错误：{metadata}')
        dist = package['dist']
        archive = published / f'{name}-0.1.0.tgz'
        if not archive.exists():
            if urlparse(dist['tarball']).hostname != 'registry.npmjs.org':
                raise SystemExit('旧版包来源必须是官方 npm registry')
            with urllib.request.urlopen(dist['tarball']) as response:
                archive.write_bytes(response.read())
        payload = archive.read_bytes()
        algorithm, expected = dist['integrity'].split('-', 1)
        if base64.b64encode(hashlib.new(algorithm, payload).digest()).decode() != expected:
            raise SystemExit(f'旧版包 integrity 不匹配：{archive}')
        provenance.append({'name': package['name'], 'version': '0.1.0', 'url': dist['tarball'],
                           'integrity': dist['integrity'], 'sha256': hashlib.sha256(payload).hexdigest()})
        run('npm', 'publish', str(archive), '--registry', registry, '--access', 'restricted',
            '--provenance=false', '--tag', 'baseline', cwd=work)
    # 发布的生成器只依赖 Node 内置模块；直接从校验后的原包解包执行，不从源码重建旧版。
    old_kit = work / 'old-kit'
    old_kit.mkdir()
    with tarfile.open(published / 'sub-app-kit-0.1.0.tgz') as archive:
        for member in archive.getmembers():
            target = (old_kit / member.name).resolve()
            if old_kit not in target.parents or not (member.isfile() or member.isdir()):
                raise SystemExit('旧版 tarball 含目录外路径或链接')
        archive.extractall(old_kit)
    app = work / 'app'
    run('node', str(old_kit / 'package/dist/bin/create.js'), 'smoke', '15176', str(app), cwd=work)
    # 模拟已有业务变更：自有页面、路由、版本、脚本与构建覆盖项。
    home = app / 'src/HomePage.vue'
    home.write_text(home.read_text().replace("import { onMounted, ref } from 'vue'", "import { onMounted, ref } from 'vue'\nimport { RouterLink } from 'vue-router'")
                    .replace('<el-table-column :label="t(\'name\')">', '<el-table-column :label="t(\'name\')" min-width="240" show-overflow-tooltip>')
                    .replace('<el-table-column prop="amount" :label="t(\'amount\')" />', '<el-table-column prop="amount" :label="t(\'amount\')" width="140" align="right" />')
                    .replace('<h3>{{ t(\'title\') }}</h3>', '<h3>{{ t(\'title\') }}</h3>\n    <RouterLink to="/apps/smoke/purchasing">采购复核</RouterLink>'))
    purchasing = home.read_text().replace("title: '订单'", "title: '采购复核'")
    purchasing = purchasing.replace('to="/apps/smoke/purchasing">采购复核', 'to="/apps/smoke/home">返回订单')
    (app / 'src/PurchasingPage.vue').write_text(purchasing)
    routes = app / 'src/sub-app.ts'
    routes.write_text(routes.read_text().replace("import HomePage from './HomePage.vue'", "import HomePage from './HomePage.vue'\nimport PurchasingPage from './PurchasingPage.vue'")
                      .replace("export const routes: RouteRecordRaw[] = [", "export const routes: RouteRecordRaw[] = [\n  { path: 'purchasing', name: 'smoke:purchasing', component: PurchasingPage },"))
    config = app / 'vite.config.ts'
    config.write_text(config.read_text().replace("apiPrefixes: ['/smoke']", "apiPrefixes: ['/smoke'], gateway: 'http://127.0.0.1:18000', shellOrigins: ['http://127.0.0.1:15174']")
                      .replace("15174'] })", "15174'] }, () => ({ build: { sourcemap: true }, define: { __PURCHASING__: JSON.stringify('采购复核') } }))"))
    package_file = app / 'package.json'
    package = json.loads(package_file.read_text())
    package['version'] = '1.7.3'
    package['scripts']['business:check'] = 'pnpm typecheck'
    write_json(package_file, package)
    run('pnpm', 'install', cwd=app)
    run('pnpm', 'build', cwd=app)
    baseline = work / 'baseline'
    shutil.copytree(app, baseline, ignore=shutil.ignore_patterns('node_modules', 'dist', '.vite'))
    source_hashes = hashes(app / 'src')
    write_json(work / 'baseline-source-sha256.json', source_hashes)
    write_json(work / 'provenance.json', {'published': provenance, 'candidate': candidate_record,
                                          'candidateManifestSha256': hashlib.sha256((candidate / 'candidate.json').read_bytes()).hexdigest()})
    # 第一个 TDD 接缝：旧工程尚无新版检查命令，必须先记录红灯，不能以零检查为升级成功。
    red = subprocess.run(['pnpm', 'format:check'], cwd=app, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (work / 'red-missing-format.log').write_bytes(red.stdout)
    if red.returncode == 0:
        raise SystemExit('基线意外已有 format:check，请重新审阅固定升级差异')
    tools = work / 'candidate-tools'
    tools.mkdir()
    write_json(tools / 'package.json', {'name': 'upgrade-reference-tools', 'private': True,
                                       'dependencies': {f'@crane199709/{name}': version for name in ('sdk', 'ui', 'sub-app-kit')}})
    run('npm', 'install', '--ignore-scripts', '--no-audit', '--no-fund', cwd=tools)
    reference = work / 'reference'
    run(str(tools / 'node_modules/.bin/forge-create-sub-app'), 'smoke', '15176', str(reference), cwd=work)
    target_package = json.loads((reference / 'package.json').read_text())
    for section in ('dependencies', 'devDependencies', 'scripts'):
        package[section].update(target_package[section])
    write_json(package_file, package)
    # 逐个复制工具/规范，不复制业务 src、路由、index、Vite 自定义配置。
    for name in ('.prettierrc.json', '.prettierignore', 'stylelint.config.mjs', 'playwright.visual.config.ts', 'e2e', 'AGENTS.md', 'docs'):
        source = reference / name
        if source.is_dir():
            shutil.copytree(source, app / name)
        else:
            shutil.copyfile(source, app / name)
    config.write_text(config.read_text().replace("apiPrefixes: ['/smoke'], ", ''))
    # 此步骤已更新依赖，需显式重写旧锁文件；CI 默认冻结会阻止实际升级。
    run('pnpm', 'install', '--no-frozen-lockfile', cwd=app)
    # 格式化是必要工具差异，先格式化旧源码，之后校验与业务基线只存在格式差异。
    run('pnpm', 'format', cwd=app)
    for command in ('format:check', 'lint', 'lint:style', 'typecheck', 'business:check', 'build'):
        run('pnpm', command, cwd=app)
    # 独立格式化基线副本后逐字节比较，防止替换页面/路由伪造升级成功。
    run(str(app / 'node_modules/.bin/prettier'), '--write', 'src', '--config', str(app / '.prettierrc.json'), cwd=baseline)
    if hashes(baseline / 'src') != hashes(app / 'src'):
        raise SystemExit('业务源码在升级时发生非格式变化')
    installed = json.loads(subprocess.check_output(['pnpm', 'list', '--depth', '0', '--json'], cwd=app))[0]
    for name in ('sdk', 'ui'):
        if installed['dependencies'][f'@crane199709/{name}']['version'] != version:
            raise SystemExit('已安装 SDK/UI 与候选版本不一致')
    if installed['devDependencies']['@crane199709/sub-app-kit']['version'] != version:
        raise SystemExit('已安装 kit 与候选版本不一致')
    # 保留两种版本的锁文件与可审阅的配置差异；dist 供真实 Shell 加载。
    write_json(work / 'upgrade-audit.json', {'businessVersion': package['version'],
              'sourcePreservedAfterFormatting': True, 'baselineSourceSha256': source_hashes,
              'upgradedSourceSha256': hashes(app / 'src'), 'package': package, 'installedPackages': installed,
              'baselineLockSha256': hashlib.sha256((baseline / 'pnpm-lock.yaml').read_bytes()).hexdigest(),
              'upgradedLockSha256': hashlib.sha256((app / 'pnpm-lock.yaml').read_bytes()).hexdigest(),
              'remoteEntrySha256': hashlib.sha256((app / 'dist/remoteEntry.js').read_bytes()).hexdigest()})
    (work / 'evidence').mkdir()
    print(f'固定旧版前端升级构建通过：{app}', flush=True)


if __name__ == '__main__':
    main()
