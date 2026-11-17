#!/usr/bin/env python3
"""真实发布 storage 0.1.0 的固定升级验收，不自动改写业务仓库。"""
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
from zipfile import ZipFile
from service_upgrade import ROOT, PACKAGE, VERSION, digest, prepare, run, verify_preservation

FIXTURES = ROOT / 'deploy/upgrade-smoke/fixtures/storage'


def old_storage(old, work):
    pom = old / 'pom.xml'
    pom.write_text(pom.read_text().replace('<dependencies>', '''<dependencies>
<dependency><groupId>io.saasforge</groupId><artifactId>forge-sdk-storage</artifactId></dependency>''', 1))
    storage = work / 'published/io/saasforge/forge-sdk-storage/0.1.0/forge-sdk-storage-0.1.0.jar'
    with ZipFile(storage) as archive:
        schema = archive.read('io/saasforge/sdk/storage/schema.sql')
    migrations = old / 'src/main/resources/db/migration'
    (migrations / 'V8__storage.sql').write_bytes(schema)
    shutil.copy2(FIXTURES / 'V9__documents.sql', migrations / 'V9__documents.sql')
    for name, layer in [('DocumentController', 'controller'), ('DocumentService', 'service'),
                        ('DocumentErrorCode', 'service'), ('DocumentMapper', 'mapper')]:
        destination = old / 'src/main/java' / PACKAGE / 'document' / layer / f'{name}.java'
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(FIXTURES / f'{name}.java', destination)
    mapper = old / 'src/main/resources/document/mapper/DocumentMapper.xml'
    mapper.parent.mkdir(parents=True)
    shutil.copy2(FIXTURES / 'DocumentMapper.xml', mapper)


def main():
    work = Path(sys.argv[1]).resolve()
    maven, upgraded = prepare(work, sys.argv[2] or None, old_setup=old_storage)
    old = work / 'old/retained-service'
    service = upgraded / 'src/main/java' / PACKAGE / 'document/service/DocumentService.java'
    text = service.read_text().replace('import java.util.UUID;', '''import java.time.Duration;
import java.util.Set;
import java.util.UUID;''').replace('public class DocumentService {', '''public class DocumentService {
    private static final StoragePurpose ATTACHMENT = new StoragePurpose(
        "document-attachment", Set.of("txt"), 1024, Duration.ofMinutes(5), 30);''')
    text = text.replace('storage.prepareUpload(filename,', 'storage.prepareUpload(ATTACHMENT, filename,')
    text = text.replace('storage.prepareDownload(id)', 'storage.prepareDownload(ATTACHMENT, id)')
    text = text.replace('storage.get(objectId)', 'storage.get(ATTACHMENT, objectId)')
    text = text.replace('    public StorageUsage usage()', '''    public StorageObjectLifecycle lifecycle(UUID id) { return storage.lifecycle(id); }
    public void expire(UUID id) { storage.delete(id); }
    public StorageObjectLifecycle restore(UUID id) { return storage.restore(ATTACHMENT, id); }
    public StorageUsage usage()''')
    service.write_text(text)
    shutil.copy2(FIXTURES / 'DocumentLifecycleController.java',
        upgraded / 'src/main/java' / PACKAGE / 'document/controller/DocumentLifecycleController.java')
    version = json.loads((work / 'baseline.json').read_text())['candidate']['version']
    sdk = work / f'm2/io/saasforge/forge-sdk-storage/{version}/forge-sdk-storage-{version}.jar'
    migrations = upgraded / 'src/main/resources/db/migration'
    with ZipFile(sdk) as archive:
        for number, resource in [(12, 'upgrade-purpose.sql'), (13, 'upgrade-retention.sql')]:
            (migrations / f'V{number}__storage_{resource[8:-4]}.sql').write_bytes(
                archive.read('io/saasforge/sdk/storage/' + resource))
        schema = archive.read('io/saasforge/sdk/storage/schema.sql').decode()
    shutil.copy2(FIXTURES / 'V14__trusted_document_purpose.sql', migrations / 'V14__trusted_document_purpose.sql')
    comments = re.findall(r'^COMMENT ON (?:TABLE|COLUMN) forge_storage_\w+[^;]*;', schema, flags=re.MULTILINE)
    assert comments, '目标 storage JAR 未提供表字段注释'
    (migrations / 'V15__storage_comments.sql').write_text('\n'.join(comments) + '\n')
    tests = upgraded / 'src/test/java' / PACKAGE
    for file in tests.glob('*Test.java'):
        file.unlink()
    shutil.copy2(FIXTURES / 'StorageUpgradeAcceptanceTest.java', tests / 'StorageUpgradeAcceptanceTest.java')
    directory = ROOT / 'backend/examples/demo-service/src/test/java/io/saasforge/demo/TestReferenceData.java'
    (tests / directory.name).write_text(directory.read_text().replace('package io.saasforge.demo;', 'package com.example.retained;'))
    maven[0] = upgraded / 'mvnw'
    run(maven + ['spotless:apply', 'package', '-DskipTests'], upgraded, work / 'build-upgraded.log')
    verify_preservation(work, upgraded)
    baseline = json.loads((work / 'baseline.json').read_text())
    old_sdk = work / 'published/io/saasforge/forge-sdk-storage/0.1.0/forge-sdk-storage-0.1.0.jar'
    version = baseline['candidate']['version']
    new_sdk = work / f'm2/io/saasforge/forge-sdk-storage/{version}/forge-sdk-storage-{version}.jar'
    new_jar = upgraded / f'target/retained-service-{VERSION}.jar'
    for jar, sdk, sdk_version in [(work / 'old-service.jar', old_sdk, '0.1.0'), (new_jar, new_sdk, version)]:
        with ZipFile(jar) as archive:
            embedded = archive.read(f'BOOT-INF/lib/forge-sdk-storage-{sdk_version}.jar')
        assert hashlib.sha256(embedded).hexdigest() == digest(sdk), '服务未内嵌实际来源的 storage 制品'
    with ZipFile(old_sdk) as archive:
        old_schema = archive.read('io/saasforge/sdk/storage/schema.sql')
    baseline['storage'] = {'baselineKind': 'published-maven-artifacts',
        'publishedVersion': '0.1.0', 'publishedStorageJarSha256': digest(old_sdk),
        'publishedSchemaSha256': hashlib.sha256(old_schema).hexdigest(),
        'candidateStorageJarSha256': digest(new_sdk), 'oldServiceJarSha256': digest(work / 'old-service.jar'),
        'upgradedServiceJarSha256': digest(new_jar),
        'businessChanges': ['显式 StoragePurpose 及绑定/下载用途核对'],
        'incrementalMigrations': {path.name: digest(path) for path in sorted(migrations.glob('*.sql')) if int(path.name.split('__')[0][1:]) > 9},
        'historicalMigrations': {path.name: digest(path) for path in sorted((old / 'src/main/resources/db/migration').glob('*.sql'))}}
    (work / 'baseline.json').write_text(json.dumps(baseline, ensure_ascii=False, indent=2) + '\n')
    run(maven + [f'-Dupgrade.work={work}', 'verify'], upgraded, work / 'verify-upgraded.log')
    print(f'已发布 storage 独立升级验收通过，来源与 HTTP 证据：{work}', flush=True)


if __name__ == '__main__':
    main()
