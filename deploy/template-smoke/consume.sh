#!/usr/bin/env bash
# 由本地发布在临时 registry 存活期间调用；生成工程全部位于仓库外。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
work=${1:?缺少发布目录}
version=${2:?缺少发布版本}
: "${MAVEN_RELEASE_URL:?}" "${NPM_RELEASE_REGISTRY:?}"
mkdir "$work/templates"
cat > "$work/template-settings.xml" <<XML
<settings><profiles><profile><id>published</id><repositories><repository><id>forge-release</id><url>$MAVEN_RELEASE_URL</url></repository></repositories></profile></profiles><activeProfiles><activeProfile>published</activeProfile></activeProfiles></settings>
XML
maven=("$root/backend/mvnw" -B -s "$work/template-settings.xml" "-Dmaven.repo.local=$work/consumer/m2")
(cd "$work/templates" && "${maven[@]}" org.apache.maven.plugins:maven-archetype-plugin:3.4.1:generate \
  -DarchetypeGroupId=io.saasforge -DarchetypeArtifactId=forge-service-archetype \
  "-DarchetypeVersion=$version" -DgroupId=com.example -DartifactId=smoke-service \
  -Dversion=1.0.0-SNAPSHOT -Dpackage=com.example.smoke \
  -DserviceName=smoke-service -DpathPrefix=smoke -DinteractiveMode=false -DarchetypeCatalog=local)
(cd "$work/templates/smoke-service" && "${maven[@]}" verify)
# CLI 从刚发布的 npm 包运行，生成应用不得使用 workspace 链接或 tarball overrides。
(cd "$work/consumer/npm" && ./node_modules/.bin/forge-create-sub-app smoke 15176 "$work/templates/smoke-sub-app")
(cd "$work/templates/smoke-sub-app" && pnpm install && pnpm format:check && pnpm lint && pnpm lint:style && pnpm typecheck && pnpm build)
printf '仓库外模板生成与构建通过：%s\n' "$work/templates"

FORGE_TEMPLATE_SERVICE="$work/templates/smoke-service/target/smoke-service-1.0.0-SNAPSHOT.jar" "$root/deploy/e2e/run.sh"
