#!/usr/bin/env bash
# 已发布旧版 storage 的独立样例；所有工程与数据仅属于本轮隔离验收。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
: "${FORGE_CANDIDATE_DIR:?请通过 scripts/release/candidate.sh use 执行}"
[[ $# -ge 1 && $# -le 2 ]] || { echo '用法：storage.sh <新的证据目录> [已下载发布输入缓存]' >&2; exit 1; }
work=$1
[[ ! -e "$work" ]] || { echo '证据目录必须尚不存在' >&2; exit 1; }
mkdir -p "$work"
python3 "$root/deploy/upgrade-smoke/storage_upgrade.py" "$work" "${2:-}"
