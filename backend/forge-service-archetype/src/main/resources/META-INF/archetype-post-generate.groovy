def project = new File(request.outputDirectory, request.artifactId)

// 规范原文不含版本占位符，生成时在头部注入平台版本（即 archetype 版本），与 forge-create-sub-app 的写法一致。
def standards = new File(project, 'docs/standards/business-service.md')
standards.setText("<!-- 平台版本 ${request.archetypeVersion}：由 forge-service-archetype 复制，不在本工程修改；平台升级时按 CHANGELOG 重新复制。 -->\n\n" + standards.getText('UTF-8'), 'UTF-8')

// 主类名按 serviceName 派生，去掉 -service 后缀后转 PascalCase：orders-service → OrdersApplication。
// archetype 的 defaultValue 不能做大小写转换，因此在生成后改写模板中的 ServiceApplication。
def serviceName = request.properties.getProperty('serviceName')
def base = serviceName.endsWith('-service') && serviceName.length() > '-service'.length()
        ? serviceName[0..<(serviceName.length() - '-service'.length())]
        : serviceName
def className = base.split('-').findAll { it }.collect { it.capitalize() }.join('') + 'Application'
def javaDir = new File(project, 'src/main/java/' + request.getPackage().replace('.', '/'))
def template = new File(javaDir, 'ServiceApplication.java')
new File(javaDir, "${className}.java").setText(template.getText('UTF-8').replace('ServiceApplication', className), 'UTF-8')
template.delete()

// archetype 不保留文件权限，恢复 Maven Wrapper 的可执行位。
new File(project, 'mvnw').setExecutable(true, false)
