#!/usr/bin/env bash
# 固定 0.1.0 样例的升级验收；不是用于改写任意业务工程的升级工具。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
work=${1:?用法：sub-app.sh <新的仓库外证据目录> [已发布0.1.0包目录]}
: "${FORGE_CANDIDATE_DIR:?先通过 candidate.sh use 启动固定候选}"
: "${NPM_RELEASE_REGISTRY:?}" "${NPM_CONFIG_USERCONFIG:?}"
python3 "$root/deploy/upgrade-smoke/sub_app.py" "$work" "${2:-}"
work=$(cd "$work" && pwd)
# 与新建工程使用同一真实后台；这里只验收前端升级，后端升级由独立场景负责。
service=${FORGE_TEMPLATE_SERVICE:-$FORGE_CANDIDATE_DIR/templates/smoke-service/target/smoke-service-1.0.0-SNAPSHOT.jar}
[[ -f "$service" ]] || { echo '缺少配套新建模板服务 JAR；先执行模板消费或设置 FORGE_TEMPLATE_SERVICE' >&2; exit 1; }
FORGE_SUB_APP_UPGRADE=1 FORGE_TEMPLATE_SUB_APP="$work/app" \
  FORGE_TEMPLATE_EVIDENCE_DIR="$work/evidence" FORGE_TEMPLATE_SERVICE="$service" \
  "$root/deploy/e2e/run.sh"
