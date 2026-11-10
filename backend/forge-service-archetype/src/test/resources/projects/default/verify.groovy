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

// 规范只有一个来源：生成工程中的副本与仓库原文只差平台版本号
def source = new File(repoRoot, 'docs/standards/business-service.md').getText('UTF-8')
def standards = new File(project, 'docs/standards/business-service.md')
assert standards.isFile() : '生成工程缺少 docs/standards/business-service.md'
def copied = standards.getText('UTF-8')
assert copied.contains("适用平台版本：${version}") : '规范文档头部未写入平台版本'
assert copied == source.replace('@project.version@', version) : '规范文档与仓库原文不一致（标题被吞或内容被过滤改写）'

def agents = new File(project, 'AGENTS.md')
assert agents.isFile() : '生成工程缺少 AGENTS.md'
def agentsText = agents.getText('UTF-8')
assert agentsText.startsWith('# ') : 'AGENTS.md 一级标题被吞掉'
assert agentsText.readLines().count { it.startsWith('## ') } >= 3 : 'AGENTS.md 二级标题被吞掉'
assert agentsText.contains("适用平台版本：${version}") : 'AGENTS.md 未写入平台版本'
assert agentsText.contains('./mvnw verify') : 'AGENTS.md 缺少构建命令'
assert agentsText.contains('](docs/standards/business-service.md)') : 'AGENTS.md 缺少规范文档链接'
assert !agentsText.contains('@project') : 'AGENTS.md 残留未替换的占位符'
return true
