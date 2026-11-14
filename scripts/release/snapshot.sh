#!/usr/bin/env bash
# 发布 Maven 开发快照，并在隔离 Forge 缓存的仓库外工程中消费实际制品。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
mode=${1:?用法：snapshot.sh local|remote}
version=$(python3 "$root/scripts/release/version.py" dev)
work=$(mktemp -d "${TMPDIR:-/tmp}/forge-snapshot.XXXXXX")
case "$mode" in
  local)
    repository="file://$work/repository"
    ;;
  remote)
    for required in MAVEN_RELEASE_URL MAVEN_RELEASE_USERNAME MAVEN_RELEASE_PASSWORD GITHUB_REPOSITORY; do
      [[ -n "${!required:-}" ]] || { echo "缺少快照发布配置：$required" >&2; exit 1; }
    done
    python3 - <<'PY'
import os
import re
repository = os.environ['GITHUB_REPOSITORY']
if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
    raise SystemExit('GITHUB_REPOSITORY 必须为 owner/repository')
expected = 'https://maven.pkg.github.com/' + repository.lower()
if os.environ['MAVEN_RELEASE_URL'].rstrip('/') != expected:
    raise SystemExit('MAVEN_RELEASE_URL 必须指向当前仓库：' + expected)
PY
    repository=$MAVEN_RELEASE_URL
    ;;
  *) echo '模式必须为 local 或 remote' >&2; exit 1 ;;
esac
printf '快照版本：%s，验证目录：%s\n' "$version" "$work"
cat > "$work/settings.xml" <<'XML'
<settings><servers><server><id>forge-release</id>
<username>${env.MAVEN_RELEASE_USERNAME}</username><password>${env.MAVEN_RELEASE_PASSWORD}</password>
</server></servers></settings>
XML
(cd "$root/backend" && ./mvnw --batch-mode -s "$work/settings.xml" \
  "-Dforge.release.url=$repository" -DskipTests deploy)
python3 - "$root" "$work" "$version" "$repository" <<'PY'
import shutil
import sys
from pathlib import Path
from xml.sax.saxutils import escape
root, work, version, repository = sys.argv[1:]
root, work = Path(root), Path(work)
consumer = work / 'consumer'
shutil.copytree(root / 'scripts/release/snapshot-consumer', consumer)
pom = consumer / 'pom.xml'
pom.write_text(pom.read_text().replace('@forge.version@', version).replace('@forge.repository@', escape(repository)))
source = Path.home() / '.m2/repository'
def ignore(directory, names):
    excluded = [name for name in names if name.endswith('.lastUpdated')]
    if Path(directory) == source / 'io':
        excluded.append('saasforge')
    return excluded
if source.exists():
    shutil.copytree(source, work / 'm2', ignore=ignore)
PY
"$root/backend/mvnw" --batch-mode -U -s "$work/settings.xml" -f "$work/consumer/pom.xml" \
  "-Dmaven.repo.local=$work/m2" test
printf '快照发布与仓库外契约消费通过：%s\n' "$version"
