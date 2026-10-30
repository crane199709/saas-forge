#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
for tool in docker java pnpm python3 curl; do command -v "$tool" >/dev/null || { echo "缺少 $tool" >&2; exit 1; }; done
# 不接收外部数据库目标；初始化只经本次创建的容器执行。
python3 - <<'PY'
import socket
for port in (15432,16379,18000,18101,18102,18201,15174,15175):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(('127.0.0.1', port))
        except OSError as error:
            raise SystemExit(f'E2E 端口 {port} 不可用: {error}')
PY
RUN_DIR=$(mktemp -d "$ROOT/deploy/e2e/run-XXXXXXXX")
COMPOSE=(docker compose -p "forge-e2e-$(basename "$RUN_DIR" | tr '[:upper:]' '[:lower:]')" -f "$ROOT/deploy/e2e/compose.yml")
# Bash 3.2 的 nounset 会拒绝空数组；下方用条件展开保留零参数语义。
PIDS=()
cleanup() {
  result=$?
  trap - EXIT INT TERM
  for pid in ${PIDS[@]+"${PIDS[@]}"}; do kill "$pid" 2>/dev/null || true; done
  for pid in ${PIDS[@]+"${PIDS[@]}"}; do wait "$pid" 2>/dev/null || true; done
  "${COMPOSE[@]}" down --volumes >"$RUN_DIR/cleanup.log" 2>&1 || true
  echo "E2E 日志：$RUN_DIR"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
(cd frontend && pnpm install --frozen-lockfile && pnpm exec playwright install chromium && pnpm build:spike)
(cd backend && ./mvnw -B -DskipTests package) >"$RUN_DIR/build.log" 2>&1
"${COMPOSE[@]}" up -d --wait
FORGE_VERSION=$(python3 - <<'PYVERSION'
import xml.etree.ElementTree as ET
print(ET.parse('backend/forge-dependencies/pom.xml').findtext('{http://maven.apache.org/POM/4.0.0}version'))
PYVERSION
)
start() {
  local module=$1 port=$2 probe=$3
  # 不继承开发环境的 Spring/数据库覆盖项，防止 Flyway 或服务误连共享资源。
  env -i PATH="$PATH" HOME="$HOME" JAVA_HOME="${JAVA_HOME:-}" java -Xms64m -Xmx256m -XX:ActiveProcessorCount=2 -jar "backend/$module/target/${module##*/}-$FORGE_VERSION.jar" \
    "--spring.config.additional-location=file:$E2E_CONFIG" \
    "--server.port=$port" ${TEMPLATE_ARGS[@]+"${TEMPLATE_ARGS[@]}"} \
    "--forge.iam.signing-key-file=$RUN_DIR/tenant.jwk" \
    "--forge.iam.platform.signing-key-file=$RUN_DIR/platform.jwk" \
    --forge.iam.sub-app.environment=e2e \
    --spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/forge \
    --spring.data.redis.host=127.0.0.1 --spring.data.redis.port=16379 >"$RUN_DIR/${module##*/}.log" 2>&1 &
  local pid=$!
  PIDS+=("$pid")
  for ((i=0; i<120; i++)); do
    if curl --silent --fail "http://127.0.0.1:$port$probe" >/dev/null; then return; fi
    if ! kill -0 "$pid" 2>/dev/null; then cat "$RUN_DIR/${module##*/}.log"; return 1; fi
    sleep 1
  done
  echo "$module 启动超时" >&2; return 1
}
E2E_CONFIG="$ROOT/deploy/e2e/application.yml"
if [[ -n "${FORGE_TEMPLATE_SERVICE:-}" ]]; then
  E2E_CONFIG="$RUN_DIR/application.yml"
  sed 's/id: demo/id: smoke/; s@/demo@/smoke@g' "$ROOT/deploy/e2e/application.yml" > "$E2E_CONFIG"
  TEMPLATE_ARGS=(--forge.iam.manifest.tokens.smoke-service=template-smoke-token)
else
  TEMPLATE_ARGS=()
fi
start forge-iam 18101 /oauth2/jwks
if [[ -n "${FORGE_TEMPLATE_SERVICE:-}" ]]; then
  source "$ROOT/deploy/template-smoke/runtime.sh"
  start_template
else
  start examples/demo-service 18201 /actuator/health
fi
start forge-platform 18102 /actuator/health
start forge-gateway 18000 /actuator/health
if [[ -n "${FORGE_TEMPLATE_SERVICE:-}" ]]; then
  sed "s/search_path = demo/search_path = smoke/; s/'demo'/'smoke'/g" deploy/e2e/seed.sql | "${COMPOSE[@]}" exec -T postgres psql -U postgres -d forge -v ON_ERROR_STOP=1
else
  "${COMPOSE[@]}" exec -T postgres psql -U postgres -d forge -v ON_ERROR_STOP=1 < deploy/e2e/seed.sql
fi
(cd frontend && pnpm exec playwright test -c playwright.main-flow.config.ts "$@")
