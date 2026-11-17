#!/usr/bin/env bash
# 独立历史 IO 样例；输入不是不存在的已发布 0.1.0 IO。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
: "${FORGE_CANDIDATE_DIR:?请通过 scripts/release/candidate.sh use 执行}"
[[ $# -ge 1 && $# -le 2 ]] || { echo '用法：io.sh <新的证据目录> [固定历史提交的干净工作树]' >&2; exit 1; }
work=${1:?用法：io.sh <新的证据目录> [固定历史提交的干净工作树]}
[[ ! -e "$work" ]] || { echo '证据目录必须尚不存在' >&2; exit 1; }
mkdir -p "$work"
python3 "$root/deploy/upgrade-smoke/io_upgrade.py" "$work" "${@:2}"
