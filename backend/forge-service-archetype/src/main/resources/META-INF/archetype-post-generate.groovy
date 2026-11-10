// 规范原文不含版本占位符，生成时在头部注入平台版本（即 archetype 版本），与 forge-create-sub-app 的写法一致。
def standards = new File(request.outputDirectory, "${request.artifactId}/docs/standards/business-service.md")
standards.setText("<!-- 平台版本 ${request.archetypeVersion}：由 forge-service-archetype 复制，不在本工程修改；平台升级时按 CHANGELOG 重新复制。 -->\n\n" + standards.getText('UTF-8'), 'UTF-8')
