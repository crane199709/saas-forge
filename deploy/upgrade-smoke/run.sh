#!/usr/bin/env bash
# 本地、PR 与发布复用；所有候选、工程与原始诊断均写入新的仓库外目录。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
exec python3 "$root/deploy/upgrade-smoke/run.py" "$@"
