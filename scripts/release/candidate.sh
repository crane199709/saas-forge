#!/usr/bin/env bash
# 候选仅供隔离消费；目标仓库发布继续使用 publish.sh remote。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
mode=${1:?用法：candidate.sh build <新目录> | use <候选目录> <命令...>}
work=${2:?缺少候选目录}
shift 2
case "$mode" in
  build)
    [[ $# == 0 ]] || { echo 'build 不接受额外参数' >&2; exit 1; }
    [[ -z "$(git -C "$root" status --porcelain --untracked-files=normal)" ]] || { echo '候选构建须先提交源码改动' >&2; exit 1; }
    [[ ! -e "$work" ]] || { echo '候选目录必须尚不存在' >&2; exit 1; }
    version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$root/frontend/package.json")
    FORGE_RELEASE_WORK="$work" FORGE_TEMPLATE_SMOKE=true bash "$root/scripts/release/publish.sh" local "v$version"
    ;;
  use)
    [[ $# -gt 0 ]] || { echo 'use 需要在临时仓库存活期间执行的命令' >&2; exit 1; }
    work=$(cd "$work" && pwd)
    python3 "$root/scripts/release/candidate_manifest.py" verify "$work"
    runtime=$(mktemp -d "${TMPDIR:-/tmp}/forge-candidate-registry.XXXXXX")
    container=
    cleanup() { if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null; fi; }
    trap cleanup EXIT
    cat > "$runtime/config.yaml" <<'YAML'
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
YAML
    container=$(docker run -d --rm -p 127.0.0.1::4873 -v "$runtime/config.yaml:/verdaccio/conf/config.yaml:ro" verdaccio/verdaccio:6)
    port=$(docker port "$container" 4873/tcp | awk -F: '{print $NF}')
    export NPM_RELEASE_REGISTRY="http://127.0.0.1:$port"
    export MAVEN_RELEASE_URL="file://$work/maven-repository"
    export NPM_CONFIG_USERCONFIG="$runtime/npmrc"
    export NPM_RELEASE_TOKEN=local-smoke-token
    printf '@crane199709:registry=%s\n//127.0.0.1:%s/:_authToken=${NPM_RELEASE_TOKEN}\n' "$NPM_RELEASE_REGISTRY" "$port" > "$NPM_CONFIG_USERCONFIG"
    for attempt in {1..60}; do
      if curl -fsS "$NPM_RELEASE_REGISTRY/-/ping" >/dev/null 2>&1; then break; fi
      sleep 1
    done
    curl -fsS "$NPM_RELEASE_REGISTRY/-/ping" >/dev/null
    for archive in "$work"/tarballs/*.tgz; do
      npm publish "$archive" --registry "$NPM_RELEASE_REGISTRY" --access restricted --provenance=false >/dev/null 2>&1
    done
    export FORGE_CANDIDATE_DIR="$work"
    printf '已校验候选制品；临时 npm registry：%s\n' "$NPM_RELEASE_REGISTRY"
    "$@"
    ;;
  *) echo '模式必须为 build 或 use' >&2; exit 1 ;;
esac
