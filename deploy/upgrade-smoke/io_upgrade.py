#!/usr/bin/env python3
"""历史未发布 IO 基线的固定验收样例，不是业务仓库的自动升级器。"""
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
from zipfile import ZipFile

from service_upgrade import ROOT, digest, run

SOURCE_COMMIT = 'd9f2b032ebae06000e476ba3fe22f4ff90e88a99'
HISTORY_VERSION = '0.2.0-io-history-d9f2b032-SNAPSHOT'
BUSINESS_VERSION = '7.5.0-SNAPSHOT'


def prepare(work, checkout=None):
    candidate = Path(os.environ['FORGE_CANDIDATE_DIR'])
    identity = json.loads((candidate / 'candidate.json').read_text())
    target_version = identity['sourceVersion']
    if checkout:
        source = Path(checkout).resolve()
        actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip()
        changes = subprocess.check_output(['git', 'status', '--porcelain'], cwd=source, text=True).strip()
        if actual != SOURCE_COMMIT or changes:
            raise SystemExit('历史工作树须处于固定提交且无已跟踪或未跟踪改动')
    else:
        source = work / 'historical-source'
        source.mkdir()
        archive = subprocess.check_output(['git', 'archive', SOURCE_COMMIT], cwd=ROOT)
        with tarfile.open(fileobj=io.BytesIO(archive)) as files:
            files.extractall(source)
    third_party = Path.home() / '.m2/repository'
    def ignore(directory, names):
        return [name for name in names if name.endswith('.lastUpdated') or
                (Path(directory) == third_party / 'io' and name == 'saasforge')]
    if third_party.exists():
        shutil.copytree(third_party, work / 'm2', ignore=ignore)
    else:
        (work / 'm2').mkdir()
    settings = work / 'settings.xml'
    settings.write_text(f'''<settings><profiles><profile><id>io-upgrade</id><repositories>
<repository><id>forge-release</id><url>{os.environ['MAVEN_RELEASE_URL']}</url><snapshots><enabled>true</enabled></snapshots></repository>
</repositories></profile></profiles><activeProfiles><activeProfile>io-upgrade</activeProfile></activeProfiles></settings>''')
    maven = [source / 'backend/mvnw', '-B', '-s', settings, f'-Dmaven.repo.local={work / "m2"}']
    run(maven + [f'-Drevision={HISTORY_VERSION}', '-pl', 'examples/demo-service', '-am', 'install', '-DskipTests'],
        source / 'backend', work / 'build-historical.log')
    source_sample = source / 'backend/examples/demo-service'
    old = work / 'old'
    shutil.copytree(source_sample, old, ignore=shutil.ignore_patterns('target', '.flattened-pom.xml'))
    # 复制历史业务与迁移，独立命名业务版本；唯一历史版本覆盖仅用于区分未发布 SDK。
    pom = old / 'pom.xml'
    text = pom.read_text().replace('${revision}', HISTORY_VERSION)
    text = text.replace('<relativePath>../../pom.xml</relativePath>', '<relativePath/>')
    text = text.replace('<artifactId>demo-service</artifactId>',
        '<artifactId>retained-io-service</artifactId>\n    <version>' + BUSINESS_VERSION + '</version>', 1)
    text = text.replace('<version>${project.version}</version>', '<version>' + HISTORY_VERSION + '</version>')
    pom.write_text(text)
    shutil.rmtree(old / 'src/test')
    # 验收只保留公开 HTTP 所需的既有容器/JWKS/授权/字典接缝。
    tests = old / 'src/test/java/io/saasforge/demo'
    tests.mkdir(parents=True)
    for name in ['SharedContainers', 'TestTokens', 'TestGrants', 'TestReferenceData']:
        shutil.copy2(source_sample / f'src/test/java/io/saasforge/demo/{name}.java', tests / f'{name}.java')
    shared = tests / 'SharedContainers.java'
    shared.write_text(shared.read_text().replace('"../../../deploy/postgres/init"',
        'System.getProperty("upgrade.source") + "/deploy/postgres/init"'))
    shutil.copy2(source / 'backend/spotless-xml.prefs', old / 'spotless-xml.prefs')
    run(maven + ['package', '-DskipTests'], old, work / 'build-old.log')
    shutil.copy2(old / f'target/retained-io-service-{BUSINESS_VERSION}.jar', work / 'old-service.jar')
    upgraded = work / 'upgraded'
    shutil.copytree(old, upgraded, ignore=shutil.ignore_patterns('target', '.flattened-pom.xml'))
    pom = upgraded / 'pom.xml'
    pom.write_text(pom.read_text().replace(HISTORY_VERSION, target_version))
    fixture = ROOT / 'deploy/upgrade-smoke/fixtures/io/IoUpgradeAcceptanceTest.java'
    shutil.copy2(fixture, upgraded / 'src/test/java/io/saasforge/demo' / fixture.name)
    run(maven + ['dependency:resolve', '-DskipTests'], upgraded, work / 'resolve-candidate.log')
    migrations = upgraded / 'src/main/resources/db/migration'
    candidate_io = work / f'm2/io/saasforge/forge-sdk-io/{target_version}/forge-sdk-io-{target_version}.jar'
    # 固定历史的 V1—V14 已含 IO task 与 storage purpose；只补当前实际缺少的三个 IO 升级。
    incremental_sources = {}
    with ZipFile(candidate_io) as archive:
        for number, resource, name in [
                (15, 'upgrade-import-backup.sql', 'import_backup'),
                (16, 'upgrade-import-rollback-preview.sql', 'import_rollback_preview'),
                (17, 'upgrade-import-rollback-execution.sql', 'import_rollback_execution')]:
            sql = archive.read('io/saasforge/sdk/io/' + resource)
            migration_name = f'V{number}__{name}.sql'
            (migrations / migration_name).write_bytes(sql)
            incremental_sources[migration_name] = {'artifact': 'forge-sdk-io', 'resource': resource,
                'sha256': hashlib.sha256(sql).hexdigest()}

    candidate_storage = work / f'm2/io/saasforge/forge-sdk-storage/{target_version}/forge-sdk-storage-{target_version}.jar'
    # IO 源文件与结果文件复用 storage；历史 purpose 已完成，只追加仍缺少的 retention。
    with ZipFile(candidate_storage) as archive:
        resource = 'io/saasforge/sdk/storage/upgrade-retention.sql'
        sql = archive.read(resource)
        (migrations / 'V18__storage_retention.sql').write_bytes(sql)
        incremental_sources['V18__storage_retention.sql'] = {'artifact': 'forge-sdk-storage',
            'resource': resource, 'sha256': hashlib.sha256(sql).hexdigest()}
    preserved = {str(p.relative_to(old)): digest(p) for p in sorted((old / 'src/main').rglob('*')) if p.is_file()}
    for relative, checksum in preserved.items():
        assert digest(upgraded / relative) == checksum, f'业务源码或已执行迁移被修改：{relative}'
    if checkout:
        assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=source, text=True).strip(), '历史构建修改了源码'
    artifacts = {}
    for path in sorted((work / 'm2/io/saasforge').rglob('*')):
        if path.is_file() and HISTORY_VERSION in path.parts and path.suffix in ('.jar', '.pom'):
            artifacts[str(path.relative_to(work / 'm2'))] = digest(path)
    baseline_jar = work / f'm2/io/saasforge/forge-sdk-io/{HISTORY_VERSION}/forge-sdk-io-{HISTORY_VERSION}.jar'
    candidate_jar = work / f'm2/io/saasforge/forge-sdk-io/{target_version}/forge-sdk-io-{target_version}.jar'
    schema_path = 'io/saasforge/sdk/io/schema.sql'
    with ZipFile(work / 'old-service.jar') as archive:
        embedded = archive.read(f'BOOT-INF/lib/forge-sdk-io-{HISTORY_VERSION}.jar')
        assert hashlib.sha256(embedded).hexdigest() == digest(baseline_jar), '旧服务未使用独立历史 IO 构建'
    with ZipFile(baseline_jar) as archive:
        schema = archive.read(schema_path)
    with ZipFile(candidate_jar) as archive:
        candidate_schema = archive.read(schema_path)
    report = {'baseline': {'kind': 'historical-unpublished-source-build', 'sourceCommit': SOURCE_COMMIT,
        'sourceVersion': '0.2.0-SNAPSHOT', 'sourceTree': subprocess.check_output(['git', 'rev-parse', SOURCE_COMMIT + '^{tree}'], cwd=ROOT, text=True).strip(),
        'buildVersion': HISTORY_VERSION,
        'versionOverride': '-Drevision=' + HISTORY_VERSION, 'sourceRoot': str(source), 'sourceChanges': [],
        'buildArguments': [f'-Drevision={HISTORY_VERSION}', '-pl', 'examples/demo-service', '-am', 'install', '-DskipTests'],
        'oldServiceJarSha256': digest(work / 'old-service.jar'),
        'ioJarSha256': digest(baseline_jar), 'ioSchemaSha256': hashlib.sha256(schema).hexdigest(),
        'builtArtifactsSha256': artifacts, 'publishedIo010': False},
        'businessVersion': BUSINESS_VERSION, 'preservedSha256': preserved,
        'candidate': {'sourceCommit': identity['sourceCommit'], 'version': target_version,
        'manifestSha256': digest(candidate / 'candidate.json'), 'ioJarSha256': digest(candidate_jar),
        'ioSchemaSha256': hashlib.sha256(candidate_schema).hexdigest(),
        'storageJarSha256': digest(candidate_storage)},
        'incrementalMigrations': incremental_sources}
    (work / 'baseline.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    run(maven + ['spotless:apply', 'package', '-DskipTests'], upgraded, work / 'build-upgraded.log')
    for relative, checksum in preserved.items():
        assert digest(upgraded / relative) == checksum, f'格式化修改了历史业务文件：{relative}'
    with ZipFile(upgraded / f'target/retained-io-service-{BUSINESS_VERSION}.jar') as archive:
        embedded = archive.read(f'BOOT-INF/lib/forge-sdk-io-{target_version}.jar')
        assert hashlib.sha256(embedded).hexdigest() == digest(candidate_jar), '升级服务未使用实际候选 IO 制品'
    report['candidate']['serviceJarSha256'] = digest(upgraded / f'target/retained-io-service-{BUSINESS_VERSION}.jar')
    (work / 'baseline.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    return maven, source, upgraded


def main():
    work = Path(sys.argv[1]).resolve()
    maven, source, upgraded = prepare(work, sys.argv[2] if len(sys.argv) > 2 else None)
    run(maven + [f'-Dupgrade.work={work}', f'-Dupgrade.source={source}', 'verify'], upgraded,
        work / 'verify-upgraded.log')
    print(f'历史未发布 IO 样例升级通过，来源与 HTTP 证据：{work}', flush=True)


if __name__ == '__main__':
    main()
