#!/usr/bin/env bash
# 只由 E2E runner 调用，数据库目标固定为该 runner 创建的 disposable 容器。
start_template() {
  "${COMPOSE[@]}" exec -T postgres psql -X -U postgres -d forge -v service=smoke -v migrator_password=smoke_migrator -v app_password=smoke_app < "$ROOT/deploy/postgres/init/lib/provision-service.sql"
  env -i PATH="$PATH" HOME="$HOME" JAVA_HOME="${JAVA_HOME:-}" FORGE_SERVICE_PORT=18201 FORGE_NACOS_ADDR=127.0.0.1:18848 \
    FORGE_DB_URL=jdbc:postgresql://127.0.0.1:15432/forge FORGE_DB_USER=smoke_app FORGE_DB_PASSWORD=smoke_app \
    FORGE_DB_MIGRATOR_USER=smoke_migrator FORGE_DB_MIGRATOR_PASSWORD=smoke_migrator FORGE_DB_SCHEMA=smoke \
    FORGE_REDIS_HOST=127.0.0.1 FORGE_REDIS_PORT=16379 FORGE_SECURITY_ISSUER=http://127.0.0.1:18000/iam \
    FORGE_SECURITY_JWK_SET_URI=http://127.0.0.1:18101/oauth2/jwks FORGE_ROCKETMQ_ENDPOINTS=127.0.0.1:18081 \
    FORGE_IAM_URI=http://127.0.0.1:18101 FORGE_MANIFEST_TOKEN=template-smoke-token \
    java -Xms64m -Xmx256m -XX:ActiveProcessorCount=2 -jar "$FORGE_TEMPLATE_SERVICE" \
    "--spring.config.additional-location=file:$ROOT/deploy/e2e/application.yml" >"$RUN_DIR/smoke-service.log" 2>&1 &
  local pid=$!
  PIDS+=("$pid")
  for ((i=0; i<120; i++)); do
    if curl --silent --fail http://127.0.0.1:18201/actuator/health >/dev/null; then return; fi
    if ! kill -0 "$pid" 2>/dev/null; then cat "$RUN_DIR/smoke-service.log"; return 1; fi
    sleep 1
  done
  echo '模板服务启动超时' >&2
  return 1
}
