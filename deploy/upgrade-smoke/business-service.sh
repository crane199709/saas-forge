#!/usr/bin/env bash
# 在 candidate.sh use 的环境里执行；所有工程和容器均属于本轮隔离验收。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
: "${FORGE_CANDIDATE_DIR:?请通过 scripts/release/candidate.sh use 执行}"
work=${1:?用法：business-service.sh <新的证据目录> [已下载发布输入缓存]}
[[ ! -e "$work" ]] || { echo '证据目录必须尚不存在' >&2; exit 1; }
mkdir -p "$work"
python3 "$root/deploy/upgrade-smoke/service_upgrade.py" "$work" "${2:-}"
