#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
if [[ "${FORGE_NOTIFICATION_E2E:-}" == 1 && -n "${FORGE_TEMPLATE_SERVICE:-}" ]]; then
  echo '通知 E2E 与模板 E2E 不能同时启用' >&2; exit 1
fi
if [[ "${FORGE_ONBOARDING_E2E:-}" == 1 && ( "${FORGE_NOTIFICATION_E2E:-}" == 1 || -n "${FORGE_TEMPLATE_SERVICE:-}" ) ]]; then
  echo '接入 E2E 不能与通知或模板 E2E 同时启用' >&2; exit 1
fi
if [[ "${FORGE_IMPORT_ROLLBACK_E2E:-}" == 1 && ( "${FORGE_NOTIFICATION_E2E:-}" == 1 || "${FORGE_ONBOARDING_E2E:-}" == 1 || -n "${FORGE_TEMPLATE_SERVICE:-}" ) ]]; then
  echo '导入回滚 E2E 必须独立运行' >&2; exit 1
fi
for tool in docker java pnpm python3 curl; do command -v "$tool" >/dev/null || { echo "缺少 $tool" >&2; exit 1; }; done
# 不接收外部数据库目标；初始化只经本次创建的容器执行。
python3 - <<'PY'
import os, socket
ports = [15432,16379,18000,18101,18102,18201,15174,15175]
if os.environ.get('FORGE_NOTIFICATION_E2E') == '1': ports += [15173,18081]
if os.environ.get('FORGE_ONBOARDING_E2E') == '1': ports += [15173]
if os.environ.get('FORGE_IMPORT_ROLLBACK_E2E') == '1': ports += [19000]
for port in ports:
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(('127.0.0.1', port))
        except OSError as error:
            raise SystemExit(f'E2E 端口 {port} 不可用: {error}')
PY
RUN_DIR=$(mktemp -d "$ROOT/deploy/e2e/run-XXXXXXXX")
COMPOSE=(docker compose -p "forge-e2e-$(basename "$RUN_DIR" | tr '[:upper:]' '[:lower:]')" -f "$ROOT/deploy/e2e/compose.yml")
if [[ "${FORGE_NOTIFICATION_E2E:-}" == 1 ]]; then
  COMPOSE+=(-f "$ROOT/deploy/e2e/compose.notification.yml")
fi
if [[ "${FORGE_IMPORT_ROLLBACK_E2E:-}" == 1 ]]; then
  COMPOSE+=(-f "$ROOT/deploy/e2e/compose.import-rollback.yml")
fi
# Bash 3.2 的 nounset 会拒绝空数组；下方用条件展开保留零参数语义。
PIDS=()
cleanup() {
  result=$?
  trap - EXIT INT TERM
  for pid in ${PIDS[@]+"${PIDS[@]}"}; do kill "$pid" 2>/dev/null || true; done
  for pid in ${PIDS[@]+"${PIDS[@]}"}; do wait "$pid" 2>/dev/null || true; done
  if [[ "${FORGE_NOTIFICATION_E2E:-}" == 1 ]]; then
    "${COMPOSE[@]}" logs --no-color >"$RUN_DIR/compose.log" 2>&1 || true
  fi
  "${COMPOSE[@]}" down --volumes >"$RUN_DIR/cleanup.log" 2>&1 || true
  echo "E2E 日志：$RUN_DIR"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
PNPM_INSTALL_ARGS=(--frozen-lockfile)
MAVEN_BUILD_ARGS=(-B -DskipTests clean package)
if [[ "${FORGE_E2E_OFFLINE:-}" == 1 ]]; then
  PNPM_INSTALL_ARGS+=(--offline)
  MAVEN_BUILD_ARGS+=(-o)
  echo 'E2E 依赖模式：离线缓存（FORGE_E2E_OFFLINE=1）'
else
  echo 'E2E 依赖模式：默认在线解析'
fi
if [[ "${FORGE_IMPORT_ROLLBACK_E2E:-}" == 1 ]]; then
  MAVEN_BUILD_ARGS+=(-pl forge-iam,forge-platform,forge-gateway,examples/demo-service -am)
fi
(cd frontend && pnpm install "${PNPM_INSTALL_ARGS[@]}" && pnpm exec playwright install chromium && pnpm build:spike)
if [[ "${FORGE_NOTIFICATION_E2E:-}" == 1 || "${FORGE_ONBOARDING_E2E:-}" == 1 ]]; then
  (cd frontend && pnpm --filter @forge/platform-console build)
fi
(cd backend && ./mvnw "${MAVEN_BUILD_ARGS[@]}") >"$RUN_DIR/build.log" 2>&1
if [[ "${FORGE_NOTIFICATION_E2E:-}" == 1 ]]; then
  "${COMPOSE[@]}" up -d --wait postgres redis rocketmq-namesrv rocketmq-broker rocketmq-proxy
  "${COMPOSE[@]}" run --rm rocketmq-init >"$RUN_DIR/rocketmq-init.log" 2>&1
  python3 - <<'PYMQ'
import socket, time
for attempt in range(120):
    try:
        with socket.create_connection(('127.0.0.1',18081), timeout=1): break
    except OSError:
        time.sleep(1)
else: raise SystemExit('通知 E2E RocketMQ Proxy 启动超时')
PYMQ
elif [[ "${FORGE_IMPORT_ROLLBACK_E2E:-}" == 1 ]]; then
  "${COMPOSE[@]}" up -d --wait postgres redis s3
  "${COMPOSE[@]}" run --rm s3-init >"$RUN_DIR/s3-init.log" 2>&1
else
  "${COMPOSE[@]}" up -d --wait
fi
# 版本只在 maven.config 的 revision 维护，POM 中是占位符。
FORGE_VERSION=$(sed -n 's/^-Drevision=//p' backend/.mvn/maven.config)
# 每次隔离运行生成独立运维令牌；不继承开发凭据，也不把随机值写入日志。
OPS_IAM_TOKEN=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')
OPS_PLATFORM_TOKEN=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')
OPS_DEMO_TOKEN=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')
# 与实际部署使用相同的集中映射；每个服务进程只接收自己的通用凭证变量。
E2E_IAM_OPS_TOKEN="$OPS_IAM_TOKEN" E2E_PLATFORM_OPS_TOKEN="$OPS_PLATFORM_TOKEN" E2E_DEMO_OPS_TOKEN="$OPS_DEMO_TOKEN" \
  python3 - "$RUN_DIR/forge-platform.yaml" <<'PYOPS'
import json, os, sys
service = 'smoke-service' if os.environ.get('FORGE_TEMPLATE_SERVICE') else 'demo-service'
tokens = {
    'forge-iam': os.environ['E2E_IAM_OPS_TOKEN'],
    'forge-platform': os.environ['E2E_PLATFORM_OPS_TOKEN'],
    service: os.environ['E2E_DEMO_OPS_TOKEN'],
}
fd = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as output:
    json.dump({'forge': {'platform': {'ops': {'tokens': tokens}}}}, output)
PYOPS
start() {
  local module=$1 port=$2 probe=$3
  local service_ops_token= service_manifest_token= config_locations="file:$E2E_CONFIG"
  case "$module" in
    forge-iam) service_ops_token=$OPS_IAM_TOKEN ;;
    forge-platform)
      service_ops_token=$OPS_PLATFORM_TOKEN
      service_manifest_token=local-platform-manifest-token
      config_locations+=",file:$RUN_DIR/forge-platform.yaml"
      ;;
    examples/demo-service)
      service_ops_token=$OPS_DEMO_TOKEN
      service_manifest_token=local-demo-manifest-token
      if [[ "${FORGE_IMPORT_ROLLBACK_E2E:-}" == 1 ]]; then
        config_locations+=",file:$ROOT/deploy/e2e/application.import-rollback.yml"
      fi
      ;;
    forge-gateway) ;;
    *) echo "未知 E2E 模块：$module" >&2; return 1 ;;
  esac
  # 不继承开发环境的 Spring/数据库覆盖项，防止 Flyway 或服务误连共享资源。
  env -i PATH="$PATH" HOME="$HOME" JAVA_HOME="${JAVA_HOME:-}" \
    FORGE_OPS_TOKEN="$service_ops_token" FORGE_MANIFEST_TOKEN="$service_manifest_token" java -Xms64m -Xmx256m -XX:ActiveProcessorCount=2 -jar "backend/$module/target/${module##*/}-$FORGE_VERSION.jar" \
    "--spring.config.additional-location=$config_locations" \
    "--server.port=$port" ${NOTIFICATION_ARGS[@]+"${NOTIFICATION_ARGS[@]}"} ${TEMPLATE_ARGS[@]+"${TEMPLATE_ARGS[@]}"} \
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
NOTIFICATION_ARGS=()
if [[ "${FORGE_NOTIFICATION_E2E:-}" == 1 ]]; then
  NOTIFICATION_ARGS=(--forge.mq.enabled=true --forge.mq.endpoints=127.0.0.1:18081 --forge.mq.ssl=false --forge.iam.platform.redirect-uri=http://127.0.0.1:15173/)
elif [[ "${FORGE_ONBOARDING_E2E:-}" == 1 ]]; then
  NOTIFICATION_ARGS=(--forge.iam.platform.redirect-uri=http://127.0.0.1:15173/)
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
# 上报异步执行，服务健康不等于权限目录已写入；种子角色必须等待本次清单登记。
MANIFEST_SERVICE=demo-service
if [[ -n "${FORGE_TEMPLATE_SERVICE:-}" ]]; then MANIFEST_SERVICE=smoke-service; fi
MANIFESTS_READY=0
for ((i=0; i<60; i++)); do
  if curl --silent --fail -H 'Authorization: Bearer local-platform-manifest-token' \
    http://127.0.0.1:18101/internal/services | python3 -c '
import json, sys
try:
    services = json.load(sys.stdin).get("data", [])
    sys.exit(0 if {sys.argv[1], "forge-platform"}.issubset(services) else 1)
except (ValueError, TypeError):
    sys.exit(1)
' "$MANIFEST_SERVICE"; then MANIFESTS_READY=1; break; fi
  sleep 1
done
if [[ "$MANIFESTS_READY" != 1 ]]; then echo 'E2E Manifest 登记超时' >&2; exit 1; fi
if [[ -n "${FORGE_TEMPLATE_SERVICE:-}" ]]; then
  sed "s/search_path = demo/search_path = smoke/; s/'demo'/'smoke'/g" deploy/e2e/seed.sql | "${COMPOSE[@]}" exec -T postgres psql -U postgres -d forge -v ON_ERROR_STOP=1
else
  "${COMPOSE[@]}" exec -T postgres psql -U postgres -d forge -v ON_ERROR_STOP=1 < deploy/e2e/seed.sql
fi
# 标准工作台聚合读取夹具不进入 MQ、接入、导入回滚或仓库外模板专项。
if [[ -z "${FORGE_TEMPLATE_SERVICE:-}" && "${FORGE_NOTIFICATION_E2E:-}" != 1 && "${FORGE_ONBOARDING_E2E:-}" != 1 && "${FORGE_IMPORT_ROLLBACK_E2E:-}" != 1 ]]; then
  "${COMPOSE[@]}" exec -T postgres psql -U postgres -d forge -v ON_ERROR_STOP=1 < deploy/e2e/seed.workbench.sql
fi
# seed 在 IAM 启动全量发布之后创建 Tenant；夹具也须明确发布无限制，缺键会被网关拒绝。
"${COMPOSE[@]}" exec -T redis redis-cli MSET \
  iam:019a2525-0000-7000-8000-000000000001:ip-allowlist '*' \
  iam:019a2525-0000-7000-8000-000000000002:ip-allowlist '*' >/dev/null
if [[ "${FORGE_NOTIFICATION_E2E:-}" == 1 ]]; then
  "${COMPOSE[@]}" exec -T postgres psql -U postgres -d forge -v ON_ERROR_STOP=1 < deploy/e2e/seed.notification.sql
  (cd frontend && pnpm exec playwright test -c playwright.notification.config.ts "$@")
elif [[ "${FORGE_ONBOARDING_E2E:-}" == 1 ]]; then
  "${COMPOSE[@]}" exec -T postgres psql -U postgres -d forge -v ON_ERROR_STOP=1 < deploy/e2e/seed.onboarding.sql
  (cd frontend && pnpm exec playwright test -c playwright.onboarding.config.ts "$@")
elif [[ "${FORGE_IMPORT_ROLLBACK_E2E:-}" == 1 ]]; then
  "${COMPOSE[@]}" exec -T postgres psql -U postgres -d forge -v ON_ERROR_STOP=1 < deploy/e2e/seed.import-rollback.sql
  (cd frontend && pnpm exec playwright test -c playwright.import-rollback.config.ts "$@")
else
  (cd frontend && pnpm exec playwright test -c playwright.main-flow.config.ts "$@")
fi
