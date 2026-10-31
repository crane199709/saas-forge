#!/usr/bin/env bash
# 质量门由调用方运行；本脚本只负责发布与仓库外消费验证。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
mode=${1:?用法：publish.sh local|remote v0.1.0}
tag=${2:?缺少版本标签}
version=$(python3 "$root/scripts/release/version.py" check "$tag")
work=$(mktemp -d "${TMPDIR:-/tmp}/forge-release.XXXXXX")
registry_container=
cleanup() {
  if [[ -n "$registry_container" ]]; then docker rm -f "$registry_container" >/dev/null; fi
}
trap cleanup EXIT
printf '发布验证目录：%s\n' "$work"
case "$mode" in
  local)
    export MAVEN_RELEASE_URL="file://$work/maven-repository"
    mkdir -p "$work/verdaccio" "$work/maven-repository"
    cat > "$work/verdaccio/config.yaml" <<'EOF'
storage: /verdaccio/storage
uplinks:
  npmjs:
    url: https://registry.npmjs.org/
packages:
  '@crane199709/*':
    access: $all
    publish: $all
  '**':
    access: $all
    proxy: npmjs
log: {type: stdout, format: pretty, level: warn}
EOF
    registry_container=$(docker run -d --rm -p 127.0.0.1::4873 \
      -v "$work/verdaccio/config.yaml:/verdaccio/conf/config.yaml:ro" verdaccio/verdaccio:6)
    port=$(docker port "$registry_container" 4873/tcp | awk -F: '{print $NF}')
    export NPM_RELEASE_REGISTRY="http://127.0.0.1:$port"
    export NPM_RELEASE_TOKEN=local-smoke-token
    export NPM_RELEASE_ACCESS=restricted
    for attempt in {1..60}; do
      if curl -fsS "$NPM_RELEASE_REGISTRY/-/ping" >/dev/null; then break; fi
      sleep 1
    done
    curl -fsS "$NPM_RELEASE_REGISTRY/-/ping" >/dev/null
    ;;
  remote)
    for required in MAVEN_RELEASE_URL MAVEN_RELEASE_USERNAME MAVEN_RELEASE_PASSWORD \
      NPM_RELEASE_REGISTRY NPM_RELEASE_TOKEN NPM_RELEASE_ACCESS GITHUB_REPOSITORY; do
      if [[ -z "${!required:-}" ]]; then
        echo "缺少发布配置：$required" >&2
        exit 1
      fi
    done
    # GITHUB_TOKEN 只能发往当前仓库，避免配置错误泄漏发布凭证。
    python3 - <<'PYTARGET'
import os
import re
repository = os.environ['GITHUB_REPOSITORY']
if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
    raise SystemExit('GITHUB_REPOSITORY 必须为 owner/repository')
expected = 'https://maven.pkg.github.com/' + repository.lower()
if os.environ['MAVEN_RELEASE_URL'] not in (expected, expected + '/'):
    raise SystemExit('MAVEN_RELEASE_URL 必须指向当前仓库：' + expected)
if os.environ['NPM_RELEASE_REGISTRY'] not in ('https://registry.npmjs.org', 'https://registry.npmjs.org/'):
    raise SystemExit('NPM_RELEASE_REGISTRY 必须为 https://registry.npmjs.org/')
if os.environ['NPM_RELEASE_ACCESS'] not in ('public', 'restricted'):
    raise SystemExit('NPM_RELEASE_ACCESS 必须明确设为 public 或 restricted')
PYTARGET
    ;;
  *) echo '模式必须为 local 或 remote' >&2; exit 1 ;;
esac
# settings 只保留环境变量引用，不落盘密钥。
cat > "$work/settings.xml" <<'EOF'
<settings><servers><server><id>forge-release</id>
<username>${env.MAVEN_RELEASE_USERNAME}</username><password>${env.MAVEN_RELEASE_PASSWORD}</password>
</server></servers></settings>
EOF
export NPM_CONFIG_USERCONFIG="$work/npmrc"
python3 - <<'PY'
import os
from urllib.parse import urlparse
url = urlparse(os.environ['NPM_RELEASE_REGISTRY'])
if url.scheme not in ('https', 'http') or not url.netloc or url.username or url.query or url.fragment:
    raise SystemExit('npm registry 必须为无凭证的 HTTP(S) URL')
path = url.path.rstrip('/') + '/'
with open(os.environ['NPM_CONFIG_USERCONFIG'], 'w') as f:
    f.write(f'@crane199709:registry={os.environ["NPM_RELEASE_REGISTRY"]}\n//{url.netloc}{path}:_authToken=${{NPM_RELEASE_TOKEN}}\n')
PY
if [[ "$mode" == remote ]]; then
  # 身份预检不能保证包写权限，但可在 Maven 发布前拒绝无效 npm 凭证。
  npm whoami --registry "$NPM_RELEASE_REGISTRY" >/dev/null
fi
(cd "$root/backend" && ./mvnw --batch-mode -s "$work/settings.xml" \
  "-Dforge.release.url=$MAVEN_RELEASE_URL" -DskipTests deploy)
(cd "$root/frontend" && pnpm --filter @crane199709/sdk --filter @crane199709/ui --filter @crane199709/sub-app-kit build)
mkdir "$work/tarballs"
for package in sdk ui sub-app-kit; do
  # pnpm pack 负责替换 workspace: 依赖与 publishConfig；npm 只发布该确定的 tarball。
  (cd "$root/frontend/packages/$package" && pnpm pack --pack-destination "$work/tarballs")
done
# 发布前检查实际 tarball，防止 workspace 协议或指向源码的入口流入仓库。
python3 - "$work/tarballs" <<'PYCHECK'
import json
import sys
import tarfile
from pathlib import Path
for path in Path(sys.argv[1]).glob('*.tgz'):
    with tarfile.open(path) as archive:
        package = json.load(archive.extractfile('package/package.json'))
        assert not package.get('private'), path.name
        assert 'workspace:' not in json.dumps(package), path.name
        targets = list(package.get('bin', {}).values())
        for export in package['exports'].values():
            targets.extend(export.values())
        for target in targets:
            assert 'package/' + target.removeprefix('./') in archive.getnames(), target
        assert all(target.endswith('.js') for target in package.get('bin', {}).values())
PYCHECK
for archive in "$work"/tarballs/*.tgz; do
  npm publish "$archive" --registry "$NPM_RELEASE_REGISTRY" --access "$NPM_RELEASE_ACCESS" --provenance=false --tag "${NPM_RELEASE_TAG:-latest}"
done
python3 "$root/scripts/release/consume.py" "$work/consumer" "$version" "$MAVEN_RELEASE_URL"
# 复用第三方下载，隔离全部 Forge 缓存；deploy 刚写入的 install 产物绝不能蒙混过关。
python3 - "$work/consumer/m2" <<'PYCACHE'
import shutil
import sys
from pathlib import Path
source = Path.home() / '.m2/repository'
def ignore(directory, names):
    excluded = [name for name in names if name.endswith('.lastUpdated')]
    if Path(directory) == source / 'io':
        excluded.append('saasforge')
    return excluded
if source.exists():
    shutil.copytree(source, sys.argv[1], ignore=ignore)
PYCACHE
"$root/backend/mvnw" --batch-mode -s "$work/settings.xml" -f "$work/consumer/maven/pom.xml" \
  "-Dmaven.repo.local=$work/consumer/m2" org.apache.maven.plugins:maven-dependency-plugin:3.9.0:resolve
(cd "$work/consumer/npm" && npm install --no-audit --no-fund && npx --no-install tsc && \
  node --input-type=module -e "import { defineSubAppConfig } from '@crane199709/sub-app-kit/vite'; defineSubAppConfig({appCode:'smoke',port:5179})({command:'build',mode:'production'})" && \
  mkdir src && printf 'export const value = 1\n' > src/index.ts && npx --no-install forge-sub-app-lint)
if [[ "$mode" == local ]]; then
  python3 - "$work/maven-repository/io/saasforge" <<'PY'
import sys
from pathlib import Path
root = Path(sys.argv[1])
for name in ['forge-gateway', 'forge-iam', 'forge-platform', 'demo-service']:
    assert not (root / name).exists(), f'不应发布服务：{name}'
for module in root.iterdir():
    jars = list(module.rglob('*.jar'))
    if jars:
        assert list(module.rglob('*-sources.jar')), f'缺少源码包：{module.name}'
PY
fi
if [[ "${FORGE_TEMPLATE_SMOKE:-false}" == true ]]; then
  [[ "$mode" == local ]] || { echo '模板冒烟仅允许临时本地发布' >&2; exit 1; }
  "$root/deploy/template-smoke/consume.sh" "$work" "$version"
fi
printf '发布与消费自检通过：%s\n' "$version"
