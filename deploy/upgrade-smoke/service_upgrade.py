#!/usr/bin/env python3
"""固定旧工程的可重复升级样例；不是业务仓库的自动覆盖工具。"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from zipfile import ZipFile

from published_maven import main as published_maven

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = Path('com/example/retained')
VERSION = '7.4.0-SNAPSHOT'


def run(command, cwd, log):
    print(f'执行：{log.name}', flush=True)
    with log.open('w') as output:
        result = subprocess.run([str(part) for part in command], cwd=cwd, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        print(log.read_text()[-12000:], file=sys.stderr)
        raise SystemExit(f'失败（{result.returncode}），完整诊断：{log}')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(work, published_cache):
    candidate = Path(os.environ['FORGE_CANDIDATE_DIR'])
    identity = json.loads((candidate / 'candidate.json').read_text())
    target_version = identity['sourceVersion']
    published_maven(work / 'published', published_cache or None)
    # 仅复用第三方缓存；发布的旧版和候选版均必须从明确来源解析。
    source = Path.home() / '.m2/repository'
    def ignore(directory, names):
        return [name for name in names if name.endswith('.lastUpdated') or
                (Path(directory) == source / 'io' and name == 'saasforge')]
    if source.exists():
        shutil.copytree(source, work / 'm2', ignore=ignore)
    else:
        (work / 'm2').mkdir()
    settings = work / 'settings.xml'
    settings.write_text(f'''<settings><profiles><profile><id>upgrade</id><repositories>
<repository><id>published-baseline</id><url>{(work / 'published').as_uri()}</url></repository>
<repository><id>forge-release</id><url>{os.environ['MAVEN_RELEASE_URL']}</url><snapshots><enabled>true</enabled></snapshots></repository>
</repositories></profile></profiles><activeProfiles><activeProfile>upgrade</activeProfile></activeProfiles></settings>''')
    maven = [ROOT / 'backend/mvnw', '-B', '-s', settings, f'-Dmaven.repo.local={work / "m2"}']
    for name, version in [('old', '0.1.0'), ('reference', target_version)]:
        directory = work / name
        directory.mkdir()
        run(maven + ['org.apache.maven.plugins:maven-archetype-plugin:3.4.1:generate',
            '-DarchetypeGroupId=io.saasforge', '-DarchetypeArtifactId=forge-service-archetype',
            f'-DarchetypeVersion={version}', '-DgroupId=com.example', '-DartifactId=retained-service',
            f'-Dversion={VERSION}', '-Dpackage=com.example.retained', '-DserviceName=retained-service',
            '-DpathPrefix=retained', '-DinteractiveMode=false', '-DarchetypeCatalog=local'], directory,
            work / f'generate-{name}.log')
    old = work / 'old/retained-service'
    reference = work / 'reference/retained-service'
    with (work / 'reference-diff.patch').open('w') as output:
        difference = subprocess.run(['diff', '-ru', '--exclude=target', str(old), str(reference)], stdout=output)
        if difference.returncode not in (0, 1):
            raise RuntimeError('参考工程差异生成失败')
    # 代表业务团队在旧脚手架上已经完成的开发，不来自新版参考工程。
    greeting = old / 'src/main/java' / PACKAGE / 'greeting/controller/GreetingController.java'
    greeting.write_text(greeting.read_text()
        .replace('import org.springframework.context.MessageSource;',
            'import org.springframework.context.MessageSource;\n'
            'import org.springframework.boot.context.properties.ConfigurationProperties;\n'
            'import org.springframework.boot.context.properties.EnableConfigurationProperties;')
        .replace('@RestController', '@RestController\n@EnableConfigurationProperties(GreetingController.BusinessProperties.class)')
        .replace('private final MessageSource messageSource;',
            'private final MessageSource messageSource;\n    private final BusinessProperties business;')
        .replace('GreetingController(MessageSource messageSource)',
            'GreetingController(MessageSource messageSource, BusinessProperties business)')
        .replace('this.messageSource = messageSource;', 'this.messageSource = messageSource;\n        this.business = business;')
        .replace('new GreetingResponse(messageSource',
            'new GreetingResponse("existing-business:" + business.invoicePrefix() + ":" + messageSource')
        .replace('    public record GreetingResponse',
            '    @ConfigurationProperties("business")\n    public record BusinessProperties(String invoicePrefix) {}\n\n    public record GreetingResponse'))
    application = old / 'src/main/resources/application.yml'
    application.write_text(application.read_text().replace('version: 1', 'version: 11') +
                           '\nbusiness:\n  invoice-prefix: KEEP-2026\n')
    (old / '.env.example').write_text('BUSINESS_INVOICE_PREFIX=KEEP-2026\n')
    pom = old / 'pom.xml'
    pom.write_text(pom.read_text().replace('<java.version>17</java.version>',
        '<java.version>17</java.version><business.contract>keep-business-7</business.contract>'))
    run(maven + ['-DskipTests', 'package'], old, work / 'build-old.log')
    old_jar = work / 'old-service.jar'
    shutil.copy2(old / f'target/retained-service-{VERSION}.jar', old_jar)
    upgraded = work / 'upgraded/retained-service'
    shutil.copytree(old, upgraded, ignore=shutil.ignore_patterns('target'))
    preserved = [greeting.relative_to(old), Path('.env.example'), Path('src/main/resources/META-INF/forge/manifest.json')]
    preserved += [path.relative_to(old) for path in (old / 'src/main/resources/db/migration').glob('*.sql')]
    before = {str(path): digest(old / path) for path in preserved}
    # 只合并父版本、两个新增必需依赖、forkCount 和格式插件，不换掉业务坐标或自定义属性。
    pom = upgraded / 'pom.xml'
    text = pom.read_text().replace('<version>0.1.0</version>', f'<version>{target_version}</version>')
    text = text.replace('</properties>', '<forkCount>2</forkCount></properties>')
    text = text.replace('<dependencies>', '''<dependencies>
<dependency><groupId>io.saasforge</groupId><artifactId>forge-sdk-ops</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>''', 1)
    text = text.replace('</plugins>', '''<plugin><groupId>com.diffplug.spotless</groupId><artifactId>spotless-maven-plugin</artifactId></plugin></plugins>''', 1)
    pom.write_text(text)
    application = upgraded / 'src/main/resources/application.yml'
    application.write_text(application.read_text().replace('forge:\n',
                            'forge:\n  ops:\n    token: ${FORGE_OPS_TOKEN}\n', 1))
    # 对照实际生成的参考工程合入请求约束；保留其余业务实现。
    controller = upgraded / 'src/main/java' / PACKAGE / 'order/controller/OrderController.java'
    text = controller.read_text().replace('import java.math.BigDecimal;', '''import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;''').replace('@RequestBody OrderRequest', '@Valid @RequestBody OrderRequest')
    text = text.replace('record OrderRequest(String title, BigDecimal amount)',
        'record OrderRequest(@NotBlank @Size(max = 200) String title, @NotNull @PositiveOrZero BigDecimal amount)')
    controller.write_text(text)
    for mapper in (upgraded / 'src/main/resources').glob('*/mapper/*.xml'):
        mapper.write_text(mapper.read_text().replace('now()', 'CURRENT_TIMESTAMP'))
    for path in ['mvnw', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties', 'spotless-xml.prefs',
                 'docs/standards/business-service.md']:
        destination = upgraded / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(reference / path, destination)
    agents = upgraded / 'AGENTS.md'
    agents.write_text((agents.read_text() + '\n' if agents.exists() else '') + (reference / 'AGENTS.md').read_text())
    # 合并当前测试骨架；业务测试在真实仓库必须保留。本固定样例的旧测试无业务定制。
    shutil.rmtree(upgraded / 'src/test')
    shutil.copytree(reference / 'src/test', upgraded / 'src/test')
    permission_test = upgraded / 'src/test/java' / PACKAGE / 'FunctionalPermissionTest.java'
    permission_test.write_text(permission_test.read_text().replace('leaf.path()).isEqualTo("home")',
        'leaf.path()).isEqualTo("overview")'))
    # 已发布旧模板缺少的默认 SDK 表只做增量；绝不复制新版 V1/V2 覆盖旧历史。
    sdk_jar = work / 'm2/io/saasforge/forge-sdk-mq' / target_version / f'forge-sdk-mq-{target_version}.jar'
    # 参考工程生成只解析 archetype；先从候选仓库解析升级后的 SDK。
    run(maven + ['-DskipTests', 'dependency:resolve'], upgraded, work / 'resolve-upgraded.log')
    with ZipFile(sdk_jar) as archive:
        mq_schema = archive.read('io/saasforge/sdk/mq/schema.sql').decode()
    dead_event = mq_schema[mq_schema.index('CREATE TABLE forge_dead_event'):]
    assert re.findall(r'CREATE TABLE (\w+)', dead_event) == ['forge_dead_event'], 'SDK schema 变化，须重新审阅增量范围'
    migration = upgraded / 'src/main/resources/db/migration'
    (migration / 'V8__forge_dead_event.sql').write_text(
        '-- 既有 Outbox/消费去重表保持不变；死信表为服务级基础设施，不启用 RLS。\n' + dead_event)
    old_tables = 'forge_outbox|forge_consumed_message|orders|order_notifications|order_categories'
    comments = []
    for initial in sorted((reference / 'src/main/resources/db/migration').glob('*.sql')):
        comments += re.findall(rf'^COMMENT ON (?:TABLE|COLUMN) (?:{old_tables})\b[^;]*;',
                               initial.read_text(), flags=re.MULTILINE)
    assert comments, '实际参考工程缺少注释来源'
    (migration / 'V9__existing_schema_comments.sql').write_text(
        '-- 旧迁移不可改写；为现有表和字段补中文描述，不改变数据及约束。\n' + '\n'.join(comments) + '\n')
    fixture = ROOT / 'deploy/upgrade-smoke/fixtures/service/UpgradeAcceptanceTest.java'
    shutil.copy2(fixture, upgraded / 'src/test/java' / PACKAGE / fixture.name)
    after = {str(path): digest(upgraded / path) for path in preserved}
    assert before == after, '升级修改了业务实现或已执行迁移'
    assert 'invoice-prefix: KEEP-2026' in application.read_text()
    assert 'business.contract' in pom.read_text()
    # 新版主类名是模板可选约定；已有主类可保留，避免不必要的重命名。
    report = {'oldVersion': '0.1.0', 'businessVersion': VERSION, 'manifestVersion': 11,
        'candidate': {'sourceCommit': identity['sourceCommit'], 'version': target_version,
                      'manifestSha256': digest(candidate / 'candidate.json')}, 'preservedSha256': before,
        'incrementalMigrations': {path.name: digest(path) for path in sorted(migration.glob('V[89]__*.sql'))},
        'sdkMqJarSha256': digest(sdk_jar), 'sdkMqSchemaSha256': hashlib.sha256(mq_schema.encode()).hexdigest()}
    (work / 'baseline.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    return maven, upgraded


def verify_preservation(work, upgraded):
    baseline = json.loads((work / 'baseline.json').read_text())
    baseline['upgradedSha256'] = {relative: digest(upgraded / relative)
                                 for relative in baseline['preservedSha256']}
    for relative, checksum in baseline['preservedSha256'].items():
        if relative.endswith(('.sql', 'manifest.json')) or relative == '.env.example':
            assert baseline['upgradedSha256'][relative] == checksum, f'历史文件被修改：{relative}'
    baseline['preservationNote'] = 'preservedSha256 为合并前/格式化前摘要；Java 可由 Spotless 改格式，业务语义经 HTTP 验证。SQL、Manifest 与环境示例须在格式化后仍字节一致。'
    (work / 'baseline.json').write_text(json.dumps(baseline, ensure_ascii=False, indent=2) + '\n')


def main():
    work = Path(sys.argv[1]).resolve()
    maven, upgraded = prepare(work, sys.argv[2] if len(sys.argv) > 2 else '')
    maven[0] = upgraded / 'mvnw'
    run(maven + ['spotless:apply', '-DskipTests', 'package'], upgraded, work / 'build-upgraded.log')
    verify_preservation(work, upgraded)
    run(maven + [f'-Dupgrade.work={work}', 'verify'], upgraded, work / 'verify-upgraded.log')
    print(f'已有 Business Service 升级通过，来源、工程和 HTTP 证据：{work}', flush=True)


if __name__ == '__main__':
    main()
