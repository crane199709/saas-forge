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
return true
