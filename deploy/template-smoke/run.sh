#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
version=$(python3 -c 'import json; print(json.load(open("frontend/packages/sdk/package.json"))["version"])')
FORGE_TEMPLATE_SMOKE=true bash scripts/release/publish.sh local "v$version" 2>&1 | tee deploy/template-smoke/run.log
