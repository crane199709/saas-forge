// 生成工程 verify 通过后，检查随脚手架带入的规范文档：存在、Markdown 标题未被 Velocity 当注释吞掉、平台版本已写入。
def project = new File(basedir, 'project/sample-service')
def parent = new File(project, 'pom.xml').getText('UTF-8') =~ /forge-dependencies<\/artifactId>\s*<version>([^<]+)</
assert parent.find() : '生成工程 POM 缺少 forge-dependencies 父 POM'
def version = parent.group(1)
assert !version.contains('@') : "生成工程 POM 未写入平台版本：${version}"

def repoRoot = basedir
while (repoRoot != null && !new File(repoRoot, 'docs/standards/business-service.md').isFile()) {
    repoRoot = repoRoot.parentFile
}
assert repoRoot != null : '找不到仓库中的 docs/standards/business-service.md'

// 规范只有一个来源：仓库原文不含版本占位符，生成时在头部注入平台版本，其余与原文一致
def source = new File(repoRoot, 'docs/standards/business-service.md').getText('UTF-8')
assert !source.contains('@project') : '仓库规范原文不应包含版本占位符'
def standards = new File(project, 'docs/standards/business-service.md')
assert standards.isFile() : '生成工程缺少 docs/standards/business-service.md'
def copied = standards.getText('UTF-8')
def header = copied.readLines()[0]
assert header.startsWith('<!-- 平台版本 ') && header.contains(version) : "规范文档头部未写入平台版本：${header}"
assert copied == header + '\n\n' + source : '规范文档与仓库原文不一致（标题被吞或内容被过滤改写）'

def agents = new File(project, 'AGENTS.md')
assert agents.isFile() : '生成工程缺少 AGENTS.md'
def agentsText = agents.getText('UTF-8')
assert agentsText.startsWith('# ') : 'AGENTS.md 一级标题被吞掉'
assert agentsText.readLines().count { it.startsWith('## ') } >= 3 : 'AGENTS.md 二级标题被吞掉'
assert agentsText.contains("适用平台版本：${version}") : 'AGENTS.md 未写入平台版本'
assert agentsText.contains('./mvnw verify') : 'AGENTS.md 缺少构建命令'
assert agentsText.contains('](docs/standards/business-service.md)') : 'AGENTS.md 缺少规范文档链接'
assert !agentsText.contains('@project') : 'AGENTS.md 残留未替换的占位符'

// 主类名按 serviceName 派生，模板中的占位类名不残留
def javaDir = new File(project, 'src/main/java/com/example/sample')
assert new File(javaDir, 'SampleApplication.java').getText('UTF-8').contains('public class SampleApplication') : '主类未按 serviceName 命名为 SampleApplication'
assert !new File(javaDir, 'ServiceApplication.java').exists() : '模板占位主类 ServiceApplication 残留'
assert !new File(javaDir, 'DemoApplication.java').exists() : '残留旧主类 DemoApplication'

// Maven Wrapper 与平台仓库同一份，mvnw 可执行
def mvnw = new File(project, 'mvnw')
assert mvnw.isFile() && mvnw.canExecute() : '生成工程缺少可执行的 mvnw'
assert new File(project, 'mvnw.cmd').isFile() : '生成工程缺少 mvnw.cmd'
def wrapper = new File(project, '.mvn/wrapper/maven-wrapper.properties')
assert wrapper.isFile() : '生成工程缺少 .mvn/wrapper/maven-wrapper.properties'
assert wrapper.getText('UTF-8') == new File(repoRoot, 'backend/.mvn/wrapper/maven-wrapper.properties').getText('UTF-8') : 'Maven Wrapper 配置与平台仓库不一致'
assert !agentsText.contains('未带 Maven Wrapper') : 'AGENTS.md 残留无 Wrapper 的说明'

// 格式规则在父 POM，XML 规则文件与平台仓库同一份；生成工程声明了 Spotless，verify 通过即说明模板本身已符合格式
def prefs = new File(project, 'spotless-xml.prefs')
assert prefs.isFile() : '生成工程缺少 spotless-xml.prefs'
assert prefs.getText('UTF-8') == new File(repoRoot, 'backend/spotless-xml.prefs').getText('UTF-8') : 'spotless-xml.prefs 与平台仓库不一致'
assert new File(project, 'pom.xml').getText('UTF-8').contains('<artifactId>spotless-maven-plugin</artifactId>') : '生成工程 POM 未声明 Spotless'

// 迁移只有两份：SDK 必需表与可整体删除的示例业务表，不带平台演进历史
def migrations = new File(project, 'src/main/resources/db/migration').list().sort()
assert migrations == ['V1__forge_sdk.sql', 'V2__example_orders.sql'] : "迁移脚本应只有 SDK 与示例两份：${migrations}"

// 在生成工程执行真实 Maven 门禁，验证父 POM 继承、生产/测试源码范围及单语句/空循环体。
def invalid = '''class BraceProbe {
    void check(boolean flag, int[] values) {
        if (flag) step();
        else step();
        for (int i = 0; i < 1; i++) step();
        for (int value : values) step();
        while (flag) step();
        do step(); while (flag);
        for (;;) ;
        while (flag) ;
    }
    void step() {}
}
'''
def valid = '''class BraceProbe {
    void check(boolean flag, int[] values) {
        if (flag) { step(); }
        else if (values.length > 0) { step(); }
        else { step(); }
        for (int i = 0; i < 1; i++) { step(); }
        for (int value : values) { step(); }
        while (flag) { step(); }
        do { step(); } while (flag);
        for (;;) {}
        while (flag) {}
    }
    void step() {}
}
'''
def validate = { String label ->
    def command = [mvnw.absolutePath, '-o', '--batch-mode', 'validate']
    def localRepo = System.getProperty('maven.repo.local')
    if (localRepo) {
        command.add("-Dmaven.repo.local=${localRepo}".toString())
    }
    def log = new File(project, "target/need-braces-${label}.log")
    def process = new ProcessBuilder(command).directory(project).redirectErrorStream(true).redirectOutput(log).start()
    def exitCode = process.waitFor()
    return [exitCode, log.getText('UTF-8')]
}
def probes = ['main', 'test'].collect { new File(project, "src/${it}/java/BraceProbe.java") }
probes.each { assert !it.exists() : "门禁测试不能覆盖现有文件：${it}" }
try {
    probes.eachWithIndex { probe, index ->
        probe.setText(invalid, 'UTF-8')
        def (exitCode, output) = validate("invalid-${index}")
        assert exitCode != 0 && output.contains('NeedBraces') : "缺少大括号未触发门禁：${probe}\n${output}"
        def report = new File(project, 'target/checkstyle-result.xml').getText('UTF-8')
        assert report.findAll(/source="com\.puppycrawl\.tools\.checkstyle\.checks\.blocks\.NeedBracesCheck"/).size() == 8 : "应报告八处大括号违规：${report}"
        assert probe.delete() : "无法清理门禁测试文件：${probe}"
    }
    probes.each { it.setText(valid, 'UTF-8') }
    def (exitCode, output) = validate('valid')
    assert exitCode == 0 : "合法大括号与 else if 链未通过门禁：${output}"
} finally {
    probes.each { it.delete() }
}
return true
