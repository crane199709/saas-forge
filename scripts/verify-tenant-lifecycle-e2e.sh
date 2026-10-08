#!/usr/bin/env bash
set -Eeuo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly compose_directory="$repository_root/deploy/acceptance"
readonly override_file="$compose_directory/tenant-lifecycle-e2e.override.yaml"
readonly project_name="saas-forge-tenant-lifecycle-$PPID-$$"
readonly work_directory="$(mktemp -d)"
readonly secret_directory="$work_directory/secrets"
readonly environment_file="$work_directory/compose.env"
readonly response_body="$work_directory/response.json"
readonly response_headers="$work_directory/response.headers"
readonly cookie_jar="$work_directory/cookies.txt"
readonly compose_log="$work_directory/compose.log"

gateway_base=""
mailpit_base=""
audit_base=""

compose() {
  docker compose --ansi never \
    --project-directory "$compose_directory" \
    --env-file "$environment_file" \
    --project-name "$project_name" \
    --file "$compose_directory/compose.yaml" \
    --file "$override_file" \
    "$@"
}

cleanup() {
  local exit_code="$?"
  compose logs --no-color >"$compose_log" 2>/dev/null || true
  if [[ "$exit_code" -ne 0 ]]; then
    compose ps --all >&2 || true
    compose logs --no-color --tail 100 \
      kafka kafka-audit-acl-init gateway iam-service tenant-access-service entitlement-service audit-service 2>/dev/null \
      | sed -E 's/(accessToken=)[^,}]*/\1[REDACTED]/g' >&2 || true
  fi
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$work_directory"
  return "$exit_code"
}

on_error() {
  local exit_code="$?"
  printf '验收失败：line=%s command=%s exit=%s\n' \
    "${BASH_LINENO[0]}" "$BASH_COMMAND" "$exit_code" >&2
  return "$exit_code"
}

on_interrupt() {
  trap - EXIT
  cleanup
  exit 130
}

random_text() {
  openssl rand -hex 24
}

build_runtime_image() {
  local module="$1"
  local image="$2"
  local context_directory="$work_directory/images/${module//\//-}"
  local candidate
  local -a runtime_jars=()
  mkdir -p "$context_directory"
  for candidate in "$repository_root/$module/target/"*.jar; do
    [[ -e "$candidate" ]] || continue
    [[ "$candidate" == *-test-fixture.jar ]] && continue
    runtime_jars+=("$candidate")
  done
  if [[ "${#runtime_jars[@]}" -ne 1 ]]; then
    printf 'Expected exactly one runtime JAR for %s, found %s\n' \
      "$module" "${#runtime_jars[@]}" >&2
    if [[ "${#runtime_jars[@]}" -gt 0 ]]; then
      printf '  %s\n' "${runtime_jars[@]}" >&2
    fi
    return 1
  fi
  cp "${runtime_jars[0]}" "$context_directory/application.jar"
  docker build --pull=false --quiet --tag "$image" \
    --file "$repository_root/deploy/docker/Dockerfile.prebuilt" "$context_directory" >/dev/null
}

run_bootstrap() {
  local profile="$1"
  local service="$2"
  local log_file="$work_directory/$service.log"
  if ! compose --profile "$profile" run --rm "$service" >"$log_file" 2>&1; then
    printf 'Bootstrap service failed: %s\n' "$service" >&2
    tail -n 120 "$log_file" \
      | sed -E 's/((accessToken|password|token|secret)[=: ]+)[^, }]+/\1[REDACTED]/Ig' >&2
    return 1
  fi
}

grant_container_secret_access() {
  local -a secret_files=("$secret_directory"/*)
  # Linux bind mount 保留宿主权限；仅向容器补充的宿主主组开放读取，避免改用 root 或全局可读。
  chmod 0640 "${secret_files[@]}"
}

uuid_v7() {
  local timestamp_ms timestamp_hex random_hex random_a variant_source variant random_b_head random_b_tail
  timestamp_ms="$(ruby -e 'puts (Time.now.to_r * 1000).to_i')"
  timestamp_hex="$(printf '%012x' "$timestamp_ms")"
  random_hex="$(openssl rand -hex 10)"
  random_a="${random_hex:0:3}"
  variant_source="${random_hex:3:1}"
  variant="$(printf '%x' "$(((16#$variant_source & 3) | 8))")"
  random_b_head="${random_hex:4:3}"
  random_b_tail="${random_hex:7:12}"
  printf '%s-%s-7%s-%s%s-%s\n' \
    "${timestamp_hex:0:8}" "${timestamp_hex:8:4}" \
    "$random_a" "$variant" "$random_b_head" "$random_b_tail"
}

write_environment() {
  local nacos_token_source
  nacos_token_source="$(openssl rand -base64 48 | tr -d '\n')"
  umask 077
  mkdir -p "$secret_directory"
  "$repository_root/saas-forge-services/iam-service/generate-service-client-secrets.sh" "$secret_directory" >/dev/null
  printf '%s\n' 'platform-admin@saas.forge.test' >"$secret_directory/platform-admin-email"
  openssl rand -base64 32 | tr -d '\n' >"$secret_directory/platform-admin-password"
  printf '\n' >>"$secret_directory/platform-admin-password"

  {
    printf 'POSTGRES_ADMIN_USER=saas.forge_e2e\n'
    printf 'POSTGRES_ADMIN_PASSWORD=%s\n' "$(random_text)"
    printf 'IAM_MIGRATOR_PASSWORD=%s\n' "$(random_text)"
    printf 'IAM_APP_PASSWORD=%s\n' "$(random_text)"
    printf 'TENANT_ACCESS_MIGRATOR_PASSWORD=%s\n' "$(random_text)"
    printf 'TENANT_ACCESS_APP_PASSWORD=%s\n' "$(random_text)"
    printf 'ENTITLEMENT_MIGRATOR_PASSWORD=%s\n' "$(random_text)"
    printf 'ENTITLEMENT_APP_PASSWORD=%s\n' "$(random_text)"
    printf 'AUDIT_MIGRATOR_PASSWORD=%s\n' "$(random_text)"
    printf 'AUDIT_APP_PASSWORD=%s\n' "$(random_text)"
    printf 'KAFKA_ADMIN_PASSWORD=%s\n' "$(random_text)"
    printf 'KAFKA_IAM_PASSWORD=%s\n' "$(random_text)"
    printf 'KAFKA_TENANT_ACCESS_PASSWORD=%s\n' "$(random_text)"
    printf 'KAFKA_ENTITLEMENT_PASSWORD=%s\n' "$(random_text)"
    printf 'KAFKA_AUDIT_PASSWORD=%s\n' "$(random_text)"
    printf 'KAFKA_AUDIT_REPLAY_PASSWORD=%s\n' "$(random_text)"
    printf 'REDIS_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_BOOTSTRAP_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_PUBLISH_USERNAME=publisher-e2e\n'
    printf 'NACOS_PUBLISH_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_IAM_USERNAME=iam-e2e\n'
    printf 'NACOS_IAM_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_TENANT_ACCESS_USERNAME=tenant-access-e2e\n'
    printf 'NACOS_TENANT_ACCESS_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_ENTITLEMENT_USERNAME=entitlement-e2e\n'
    printf 'NACOS_ENTITLEMENT_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_AUDIT_USERNAME=audit-e2e\n'
    printf 'NACOS_AUDIT_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_GATEWAY_USERNAME=gateway-e2e\n'
    printf 'NACOS_GATEWAY_PASSWORD=%s\n' "$(random_text)"
    printf 'NACOS_AUTH_IDENTITY_KEY=identity-key-e2e\n'
    printf 'NACOS_AUTH_IDENTITY_VALUE=%s\n' "$(random_text)"
    printf 'NACOS_AUTH_TOKEN=%s\n' "$(printf '%s' "$nacos_token_source" | openssl base64 -A)"
    printf 'E2E_HOST_GID=%s\n' "$(id -g)"
    printf 'IAM_JWT_ISSUER=https://api.saas.forge.test\n'
    printf 'IAM_JWT_PEM_KEY_VERSION_REF=local/e2e/pem/1\n'
    printf 'IAM_JWT_PEM_PRIVATE_KEY_FILE=%s\n' "$secret_directory/iam-jwt-private-key.pem"
    printf 'IAM_PLATFORM_ADMIN_EMAIL_FILE=%s\n' "$secret_directory/platform-admin-email"
    printf 'IAM_PLATFORM_ADMIN_PASSWORD_FILE=%s\n' "$secret_directory/platform-admin-password"
    printf 'IAM_PLATFORM_ADMIN_RESET_REQUEST_ID_FILE=%s\n' "$secret_directory/platform-admin-reset-request-id"
    printf 'IAM_PLATFORM_ADMIN_RESET_PASSWORD_FILE=%s\n' "$secret_directory/platform-admin-reset-password"
    printf 'IAM_SERVICE_CLIENT_ID_FILE=%s\n' "$secret_directory/iam-client-id"
    printf 'IAM_SERVICE_CLIENT_SECRET_FILE=%s\n' "$secret_directory/iam-client-secret"
    printf 'TENANT_ACCESS_SERVICE_CLIENT_ID_FILE=%s\n' "$secret_directory/tenant-access-client-id"
    printf 'TENANT_ACCESS_SERVICE_CLIENT_SECRET_FILE=%s\n' "$secret_directory/tenant-access-client-secret"
    printf 'ENTITLEMENT_SERVICE_CLIENT_ID_FILE=%s\n' "$secret_directory/entitlement-client-id"
    printf 'ENTITLEMENT_SERVICE_CLIENT_SECRET_FILE=%s\n' "$secret_directory/entitlement-client-secret"
  } >"$environment_file"
}

wait_for_gateway() {
  local status
  for _ in $(seq 1 180); do
    status="$(curl --connect-timeout 1 --max-time 2 --silent --output /dev/null --write-out '%{http_code}' \
      "$gateway_base/.well-known/jwks.json" || true)"
    if [[ "$status" == "200" ]]; then
      return 0
    fi
    sleep 2
  done
  echo "Gateway 未在预期时间内 Ready" >&2
  return 1
}

wait_for_audit_health() {
  local group="$1"
  local expected_status="$2"
  local status
  for _ in $(seq 1 90); do
    status="$(curl --connect-timeout 1 --max-time 5 --silent \
      --output "$response_body" --write-out '%{http_code}' \
      "$audit_base/actuator/health/$group" || true)"
    if [[ "$status" == "$expected_status" ]]; then
      return 0
    fi
    sleep 2
  done
  echo "Audit $group 预期 HTTP ${expected_status}，实际为 $status" >&2
  cat "$response_body" >&2 2>/dev/null || true
  return 1
}

wait_for_audit_condition() {
  local condition="$1"
  local description="$2"
  local satisfied
  for _ in $(seq 1 360); do
    satisfied="$(postgres_value audit_db "SELECT ($condition)")"
    if [[ "$satisfied" == "t" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "Audit 条件未在预期时间内满足：$description" >&2
  return 1
}

publish_controlled_kafka_line() {
  local topic="$1"
  local line="$2"
  printf '%s\n' "$line" | compose exec -T kafka bash -eu -c '
    config="$(mktemp)"
    trap '\''rm -f "$config"'\'' EXIT
    chmod 0600 "$config"
    printf "%s\n" \
      "security.protocol=SASL_PLAINTEXT" \
      "sasl.mechanism=PLAIN" \
      "sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username=\"admin\" password=\"${KAFKA_ADMIN_PASSWORD}\";" \
      >"$config"
    /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server kafka:9092 --producer.config "$config" --topic "$1" \
      --property parse.key=true --property key.separator="|" \
      --producer-property max.block.ms=10000 \
      --producer-property delivery.timeout.ms=10000 >/dev/null
  ' bash "$topic"
}

republish_outbox_event() {
  local database="$1"
  local table="$2"
  local event_type="$3"
  local subject_id="$4"
  local topic="$5"
  local line
  line="$(compose exec -T postgres sh -eu -c '
    psql --username "$POSTGRES_USER" --dbname "$1" --tuples-only --no-align \
      --field-separator="|" \
      --command "SELECT ordering_key, event_snapshot::text FROM $4
                  WHERE event_snapshot->>\$\$type\$\$ = \$\$${2}\$\$
                    AND event_snapshot->\$\$data\$\$->>\$\$tenantId\$\$ = \$\$${3}\$\$
                  ORDER BY occurred_at LIMIT 1"
  ' sh "$database" "$event_type" "$subject_id" "$table")"
  [[ -n "$line" ]]
  publish_controlled_kafka_line "$topic" "$line"
}

wait_for_service_started() {
  local service="$1"
  local port status path
  for _ in $(seq 1 180); do
    # 结构化日志不会保留 Spring 启动原文，使用已映射的就绪端点判定。
    if [[ "$service" == "audit-service" || "$service" == "gateway" ]]; then
      port="$(compose port "$service" 8080 2>/dev/null | sed 's/.*://')"
      path=/actuator/health/readiness
      # Gateway 仅开放正式路由，沿用下方 wait_for_gateway 的公开 JWKS 探针。
      if [[ "$service" == "gateway" ]]; then path=/.well-known/jwks.json; fi
      status="$(curl --connect-timeout 1 --max-time 2 --silent --output /dev/null \
        --write-out '%{http_code}' "http://127.0.0.1:$port$path" || true)"
      if [[ "$status" == "200" ]]; then
        return 0
      fi
    elif compose logs --no-color "$service" 2>/dev/null | grep 'Started .*Application' >/dev/null; then
      return 0
    fi
    if [[ "$(compose ps --all --format json "$service" | jq -r 'select(.Service == $service) | .State' --arg service "$service")" == "exited" ]]; then
      echo "服务 $service 启动期间已退出" >&2
      return 1
    fi
    sleep 2
  done
  echo "服务 $service 未在预期时间内启动" >&2
  return 1
}

wait_for_redis_revocation_ready() {
  local ready
  for _ in $(seq 1 60); do
    ready="$(compose exec -T redis sh -eu -c '
      redis-cli --no-auth-warning -a "$REDIS_PASSWORD" GET \
        sf:dev:iam-service:revocation-index-ready:v1:state
    ' 2>/dev/null | tr -d '\r')"
    if [[ "$ready" == "1" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "IAM Revocation Index 未在预期时间内恢复 Ready" >&2
  return 1
}

verify_revocation_index_not_ready() (
  # IAM 定时用 SET 重建索引；禁止该写入以保持故障，但保留 JWKS 服务和 Redis 读取。
  # 仅作用于本脚本创建的隔离 Redis；退出时恢复权限，不覆盖主脚本的清理 trap。
  restore_redis_set() {
    compose exec -T redis sh -eu -c '
      redis-cli -e --no-auth-warning -a "$REDIS_PASSWORD" ACL SETUSER default +set >/dev/null
    '
  }
  trap restore_redis_set EXIT
  compose exec -T redis sh -eu -c '
    redis-cli -e --no-auth-warning -a "$REDIS_PASSWORD" ACL SETUSER default -set >/dev/null
    redis-cli -e --no-auth-warning -a "$REDIS_PASSWORD" MSET \
      sf:dev:iam-service:revocation-index-ready:v1:state 0 >/dev/null
  '
  request 503 POST /api/v1/platform/tenants "$probe_body" "$platform_token" "$(uuid_v7)"
  assert_json '.code == "TOKEN_REVOCATION_STATUS_UNAVAILABLE"'
)

postgres_value() {
  local database="$1"
  local query="$2"
  compose exec -T postgres sh -eu -c '
    psql --username "$POSTGRES_USER" --dbname "$1" --tuples-only --no-align --command "$2"
  ' sh "$database" "$query" | tr -d '[:space:]'
}

wait_for_revocation_worker_completion() {
  local tenant_id="$1"
  local completed
  for _ in $(seq 1 60); do
    completed="$(postgres_value iam_db \
      "SELECT EXISTS (SELECT 1 FROM iam_user_session_revocations workflow
       JOIN iam_revocation_fences fence USING (revocation_request_id)
       WHERE fence.target_type = 'TENANT' AND fence.target_id = '$tenant_id'
         AND workflow.revocation_status = 'COMPLETED')")"
    if [[ "$completed" == "t" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "IAM Session Revocation 未在预期时间内由重启后的 Worker 完成" >&2
  return 1
}

wait_for_tenant_suspension_worker_completion() {
  local tenant_id="$1"
  local completed
  for _ in $(seq 1 60); do
    completed="$(postgres_value tenant_access_db \
      "SELECT EXISTS (SELECT 1 FROM tenant_lifecycle_workflows
       WHERE tenant_id = '$tenant_id' AND lifecycle_action = 'SUSPEND'
         AND workflow_status = 'COMPLETED')")"
    if [[ "$completed" == "t" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "Tenant Suspension 未在预期时间内由重启后的 Worker 完成" >&2
  return 1
}

start_service() {
  local service="$1"
  local attempt
  for attempt in 1 2 3; do
    compose up --detach "$service" >/dev/null
    if wait_for_service_started "$service"; then
      return 0
    fi
    echo "服务 $service 第 $attempt 次启动失败，等待基础设施稳定后重试" >&2
    sleep 5
  done
  echo "服务 $service 连续三次启动失败" >&2
  return 1
}

request() {
  local expected_status="$1"
  local method="$2"
  local path="$3"
  local body="${4:-}"
  local bearer="${5:-}"
  local idempotency_key="${6:-}"
  local browser_origin='https://console.saas.forge.test'
  local status
  if [[ "$path" == "/api/v1/auth/password-changes" || "$body" == *'"contextType":"PLATFORM"'* \
      || "$body" == *'"sessionSlot":"PLATFORM"'* ]]; then
    browser_origin='https://platform.saas.forge.test'
  fi
  local arguments=(
    --silent --show-error
    --request "$method"
    --dump-header "$response_headers"
    --output "$response_body"
    --write-out '%{http_code}'
    --header 'Content-Type: application/json'
    --header 'X-SF-CSRF: 1'
    --header "Origin: $browser_origin"
    --header 'Sec-Fetch-Site: same-site'
    --cookie "$cookie_jar"
    --cookie-jar "$cookie_jar"
  )
  if [[ -n "$body" ]]; then
    arguments+=(--data-binary "$body")
  fi
  if [[ -n "$bearer" ]]; then
    arguments+=(--header "Authorization: Bearer $bearer")
  fi
  if [[ -n "$idempotency_key" ]]; then
    arguments+=(--header "Idempotency-Key: $idempotency_key")
  fi
  status="$(curl "${arguments[@]}" "$gateway_base$path")"
  if [[ "$status" != "$expected_status" ]]; then
    echo "请求 $method $path 预期 HTTP ${expected_status}，实际为 $status" >&2
    jq '{status,code,title,detail,traceId}' "$response_body" >&2 2>/dev/null || true
    return 1
  fi
}

wait_for_resume_after_redis_recovery() {
  local tenant_id="$1"
  local bearer="$2"
  local idempotency_key="$3"
  local status
  for _ in $(seq 1 30); do
    status="$(curl --silent --show-error --max-time 10 \
      --request DELETE \
      --dump-header "$response_headers" \
      --output "$response_body" \
      --write-out '%{http_code}' \
      --header 'Content-Type: application/json' \
      --header 'X-SF-CSRF: 1' \
      --header 'Origin: https://console.saas.forge.test' \
      --header 'Sec-Fetch-Site: same-site' \
      --header "Authorization: Bearer $bearer" \
      --header "Idempotency-Key: $idempotency_key" \
      --cookie "$cookie_jar" \
      --cookie-jar "$cookie_jar" \
      "$gateway_base/api/v1/platform/tenants/$tenant_id/suspensions")" || status=000
    if [[ "$status" == "200" ]]; then
      return 0
    fi
    if [[ "$status" == "000" ]] \
        || { [[ "$status" == "403" ]] \
          && jq --exit-status '.code == "PLATFORM_AUTHORIZATION_DENIED"' "$response_body" >/dev/null; } \
        || { [[ "$status" == "503" ]] \
          && jq --exit-status '.code == "TOKEN_REVOCATION_STATUS_UNAVAILABLE"' "$response_body" >/dev/null; }; then
      sleep 1
      continue
    fi
    echo "等待 Redis 恢复后 Resume Tenant 时预期 HTTP 200/403/503，实际为 $status" >&2
    jq '{status,code,title,detail,traceId}' "$response_body" >&2 2>/dev/null || true
    return 1
  done
  echo "Redis 恢复后 Platform 授权链未在预期时间内恢复" >&2
  return 1
}

wait_for_suspension_completion_losing_response() {
  local tenant_id="$1"
  local bearer="$2"
  local idempotency_key="$3"
  local status
  for _ in $(seq 1 60); do
    status="$(curl --silent --show-error \
      --request POST \
      --output /dev/null \
      --write-out '%{http_code}' \
      --header 'Content-Type: application/json' \
      --header 'X-SF-CSRF: 1' \
      --header 'Origin: https://console.saas.forge.test' \
      --header 'Sec-Fetch-Site: same-site' \
      --header "Authorization: Bearer $bearer" \
      --header "Idempotency-Key: $idempotency_key" \
      "$gateway_base/api/v1/platform/tenants/$tenant_id/suspensions")"
    if [[ "$status" == "200" ]]; then
      return 0
    fi
    if [[ "$status" != "503" ]]; then
      echo "等待 Tenant Suspension 时预期 HTTP 200/503，实际为 $status" >&2
      return 1
    fi
    sleep 1
  done
  echo "Tenant Suspension 未在预期时间内由 Worker 完成" >&2
  return 1
}

assert_json() {
  local expression="$1"
  if ! jq --exit-status "$expression" "$response_body" >/dev/null; then
    echo "JSON 断言失败：$expression" >&2
    jq '{status,code,title,detail,traceId}' "$response_body" >&2 2>/dev/null || true
    return 1
  fi
}

login() {
  local email="$1"
  local password="$2"
  local context_type="$3"
  request 200 POST /api/v1/auth/login \
    "$(jq -cn --arg email "$email" --arg password "$password" --arg context "$context_type" \
      '{email:$email,password:$password,contextType:$context}')"
}

decode_jwt_claims() {
  local token="$1"
  local payload
  payload="$(printf '%s' "$token" | cut -d. -f2 | tr '_-' '/+')"
  case $((${#payload} % 4)) in
    2) payload="${payload}==" ;;
    3) payload="${payload}=" ;;
  esac
  printf '%s' "$payload" | openssl base64 -d -A
}

wait_for_mail() {
  local recipient="$1"
  local messages message_id message
  for _ in $(seq 1 60); do
    messages="$(curl --silent --show-error "$mailpit_base/api/v1/messages")"
    message_id="$(printf '%s' "$messages" | jq -r --arg recipient "$recipient" \
      '.messages[]? | select(any(.To[]?; .Address == $recipient)) | .ID' | head -n 1)"
    if [[ -n "$message_id" ]]; then
      message="$(curl --silent --show-error "$mailpit_base/api/v1/message/$message_id")"
      printf '%s' "$message"
      return 0
    fi
    sleep 1
  done
  echo "Mailpit 未收到 Tenant Admin Password Setup 邮件" >&2
  return 1
}

assert_sensitive_material_absent() {
  local dump_file="$work_directory/database-dump.txt"
  local application_log="$work_directory/application.log"
  local value
  compose exec -T postgres sh -eu -c '
    for database in iam_db tenant_access_db entitlement_db audit_db; do
      pg_dump --username "$POSTGRES_USER" --data-only --inserts "$database"
    done
  ' >"$dump_file"
  compose logs --no-color iam-service tenant-access-service entitlement-service audit-service gateway \
    >"$application_log"

  for value in \
    "$initial_password" "$platform_password" "$tenant_password" "$password_setup_token" \
    "$(<"$secret_directory/iam-client-secret")" \
    "$(<"$secret_directory/tenant-access-client-secret")" \
    "$(<"$secret_directory/entitlement-client-secret")"; do
    if grep -F -- "$value" "$dump_file" "$application_log" "$business_response_log" >/dev/null; then
      echo "数据库、应用日志或业务 API 响应中发现敏感明文" >&2
      return 1
    fi
  done
}

trap on_error ERR
trap cleanup EXIT
trap on_interrupt INT TERM

for command in docker curl jq ruby openssl; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "缺少验收依赖命令: $command" >&2
    exit 1
  }
done

write_environment
touch "$cookie_jar"
business_response_log="$work_directory/business-responses.jsonl"
touch "$business_response_log"

echo "[1/13] 校验隔离 Compose、挂载 Secret 与显式引导任务"
compose config --quiet
compose --profile bootstrap --profile service-client-bootstrap config --format json | jq --exit-status '
  .services["iam-platform-admin-bootstrap"].profiles == ["bootstrap"] and
  .services["iam-reserved-service-client-bootstrap"].profiles == ["service-client-bootstrap"] and
  ([.services["iam-platform-admin-bootstrap"], .services["iam-reserved-service-client-bootstrap"],
    .services["iam-service"], .services["tenant-access-service"], .services["entitlement-service"]]
    | all(.group_add | any(test("^[0-9]+$")))) and
  ([.services["iam-reserved-service-client-bootstrap"].volumes[] | select(.read_only == true)] | length == 6) and
  ([.services["iam-service"].volumes[], .services["tenant-access-service"].volumes[],
    .services["entitlement-service"].volumes[] | select(.read_only == true)] | length >= 7) and
  (.services.mailpit.image | startswith("axllent/mailpit:"))
' >/dev/null

echo "[2/13] 在全新 PostgreSQL 数据卷迁移并初始化 IAM Signing Key"
COMPOSE_PROJECT_NAME="$project_name" \
LOCAL_COMPOSE_ENV_FILE="$environment_file" \
LOCAL_COMPOSE_OVERRIDE_FILE="$override_file" \
  bash "$repository_root/scripts/initialize-local-iam-signing-key.sh" >/dev/null
grant_container_secret_access

echo "[3/13] 显式引导 Platform Admin 与三个保留服务 Client"
"$repository_root/mvnw" --batch-mode --no-transfer-progress \
  -pl gateway,saas-forge-services/iam-service,saas-forge-services/tenant-access-service,saas-forge-services/entitlement-service,saas-forge-services/audit-service \
  -am package -DskipTests >"$work_directory/maven-package.log"
build_runtime_image gateway saas.forge/gateway:local
build_runtime_image saas-forge-services/iam-service saas.forge/iam-service:local
build_runtime_image saas-forge-services/tenant-access-service saas.forge/tenant-access-service:local
build_runtime_image saas-forge-services/entitlement-service saas.forge/entitlement-service:local
build_runtime_image saas-forge-services/audit-service saas.forge/audit-service:local
run_bootstrap bootstrap iam-platform-admin-bootstrap
run_bootstrap service-client-bootstrap iam-reserved-service-client-bootstrap
initial_password="$(<"$secret_directory/platform-admin-password")"
conflicting_password="$(openssl rand -base64 32 | tr -d '\n')"
printf '%s\n' "$conflicting_password" >"$secret_directory/platform-admin-password-conflict"
grant_container_secret_access
if IAM_PLATFORM_ADMIN_PASSWORD_FILE="$secret_directory/platform-admin-password-conflict" \
  compose --profile bootstrap run --rm iam-platform-admin-bootstrap >/dev/null 2>&1; then
  echo "不同 Platform Admin 初始凭证不应被引导任务覆盖" >&2
  exit 1
fi
unset conflicting_password

echo "[4/13] 启动真实拓扑并完成 Platform Admin 首次改密"
for service in iam-service tenant-access-service entitlement-service audit-service gateway; do
  start_service "$service"
done
gateway_port="$(compose port gateway 8080 | sed 's/.*://')"
mailpit_port="$(compose port mailpit 8025 | sed 's/.*://')"
audit_port="$(compose port audit-service 8080 | sed 's/.*://')"
gateway_base="http://127.0.0.1:$gateway_port"
mailpit_base="http://127.0.0.1:$mailpit_port"
audit_base="http://127.0.0.1:$audit_port"
wait_for_gateway
wait_for_audit_health readiness 200
wait_for_audit_health liveness 200
login 'platform-admin@saas.forge.test' "$initial_password" PLATFORM
assert_json '.contextState == "PASSWORD_CHANGE_REQUIRED" and (has("accessToken") | not)'
platform_password="Platform-$(openssl rand -hex 16)"
request 204 POST /api/v1/auth/password-changes \
  "$(jq -cn --arg password "$platform_password" '{newPassword:$password}')"
login 'platform-admin@saas.forge.test' "$platform_password" PLATFORM
assert_json '.contextState == "ACCESS_TOKEN_ISSUED" and (.accessToken | length > 100)'
platform_token="$(jq -r '.accessToken' "$response_body")"

echo "[5/13] 通过平台 API 创建并激活 max_users Quota Definition 与 Plan"
request 201 POST /api/v1/platform/quota-definitions '{"code":"max_users"}' \
  "$platform_token" "$(uuid_v7)"
quota_definition_id="$(jq -r '.id' "$response_body")"
cat "$response_body" >>"$business_response_log"
request 200 POST "/api/v1/platform/quota-definitions/$quota_definition_id/activations" '' \
  "$platform_token" "$(uuid_v7)"
cat "$response_body" >>"$business_response_log"
request 201 POST /api/v1/platform/plans \
  "$(jq -cn --arg id "$quota_definition_id" \
    '{code:"tenant-e2e",displayName:"Tenant E2E",quotaLimits:[{quotaDefinitionId:$id,limit:1}]}')" \
  "$platform_token" "$(uuid_v7)"
plan_id="$(jq -r '.id' "$response_body")"
cat "$response_body" >>"$business_response_log"
request 200 POST "/api/v1/platform/plans/$plan_id/activations" '' \
  "$platform_token" "$(uuid_v7)"
cat "$response_body" >>"$business_response_log"

echo "[6/13] 创建 PENDING Tenant、首个 ACTIVE Subscription 并初始化管理员"
tenant_expires_at="$(ruby -rtime -e 'puts (Time.now.utc + 86400).iso8601')"
request 201 POST /api/v1/platform/tenants \
  "$(jq -cn --arg expiresAt "$tenant_expires_at" \
    '{displayName:"Tenant E2E",expiresAt:$expiresAt}')" \
  "$platform_token" "$(uuid_v7)"
tenant_id="$(jq -r '.id' "$response_body")"
assert_json '.status == "PENDING"'
cat "$response_body" >>"$business_response_log"
request 201 POST "/api/v1/platform/tenants/$tenant_id/subscriptions" \
  "$(jq -cn --arg planId "$plan_id" '{planId:$planId}')" \
  "$platform_token" "$(uuid_v7)"
assert_json '.status == "ACTIVE"'
cat "$response_body" >>"$business_response_log"
tenant_admin_email='tenant-admin@saas.forge.test'
request 200 POST "/api/v1/platform/tenants/$tenant_id/administrator-initializations" \
  "$(jq -cn --arg email "$tenant_admin_email" \
    '{administratorEmail:$email,administratorDisplayName:"Tenant Admin"}')" \
  "$platform_token" "$(uuid_v7)"
assert_json '.status == "ACTIVE"'
cat "$response_body" >>"$business_response_log"

compose exec -T postgres sh -eu -c '
  psql --username "$POSTGRES_USER" --dbname tenant_access_db --tuples-only --no-align \
    --set=tenant_id="'"$tenant_id"'" <<SQL | grep -qx t
      SELECT (tenant.tenant_status = '\''ACTIVE'\'')
         AND (membership.membership_status = '\''ENABLED'\'')
         AND (role.role_key = '\''TENANT_ADMINISTRATOR'\'' AND role.system_managed)
         AND (assignment.assigned_at IS NOT NULL)
         AND (initial.membership_id = membership.id)
      FROM tenants tenant
      JOIN memberships membership ON membership.tenant_id = tenant.id
      JOIN membership_role_assignments assignment ON assignment.membership_id = membership.id
      JOIN tenant_roles role ON role.id = assignment.role_id
      JOIN initial_tenant_administrators initial ON initial.tenant_id = tenant.id
      WHERE tenant.id = :'"'"'tenant_id'"'"';
SQL
' >/dev/null

echo "[7/13] 从 Mailpit Fragment 链接完成 Password Setup 并验证 Tenant Context"
mail_message="$(wait_for_mail "$tenant_admin_email")"
password_setup_link="$(printf '%s' "$mail_message" | ruby -rjson -rcgi -e '
  message = JSON.parse(STDIN.read)
  body = [message["HTML"], message["Text"]].compact.join("\n")
  link = CGI.unescapeHTML(body)[%r{https://console\.saas\.forge\.test/password-setup#token=[A-Za-z0-9_-]+}]
  abort "Password Setup 邮件缺少固定 HTTPS Fragment 链接" unless link
  puts link
')"
password_setup_token="${password_setup_link#*#token=}"
curl --silent --show-error --fail --dump-header "$response_headers" "$gateway_base/password-setup" \
  --output "$work_directory/password-setup-page.html"
# curl 按 HTTP CRLF 写入 Header；先标准化换行，避免 BSD grep 与 GNU grep 对 \r 转义解释不同。
tr -d '\r' <"$response_headers" | grep -Fiqx 'Referrer-Policy: no-referrer'
tenant_password="Tenant-$(openssl rand -hex 16)"
request 204 POST /api/v1/auth/password-setups \
  "$(jq -cn --arg token "$password_setup_token" --arg password "$tenant_password" \
    '{token:$token,newPassword:$password}')" '' "$(uuid_v7)"
rm -f "$cookie_jar"
touch "$cookie_jar"
login "$tenant_admin_email" "$tenant_password" TENANT
assert_json '.contextState == "ACCESS_TOKEN_ISSUED" and (.accessToken | length > 100)'
tenant_token="$(jq -r '.accessToken' "$response_body")"
tenant_claims="$(decode_jwt_claims "$tenant_token")"
printf '%s' "$tenant_claims" | jq --exit-status --arg tenantId "$tenant_id" \
  '.tenantId == $tenantId and (.membershipId | length > 0)' >/dev/null

echo "[8/13] 验证 Suspension、Fence、Worker 接管与 Gateway fail-closed 闭环"
# 第二个 Refresh Token Family 来自独立浏览器；同槽位携带活动 Cookie 再登录应被拒绝。
mv "$cookie_jar" "$work_directory/first-tenant-session.cookies"
touch "$cookie_jar"
login "$tenant_admin_email" "$tenant_password" TENANT
assert_json '.contextState == "ACCESS_TOKEN_ISSUED" and (.accessToken | length > 100)'
tenant_token_second_family="$(jq -r '.accessToken' "$response_body")"

probe_body="$(jq -cn --arg expiresAt "$tenant_expires_at" \
  '{displayName:"Revocation Forwarding Probe",expiresAt:$expiresAt}')"
request 403 POST /api/v1/platform/tenants "$probe_body" "$tenant_token" "$(uuid_v7)"
assert_json '.code == "PLATFORM_AUTHORIZATION_DENIED"'
request 403 POST /api/v1/platform/tenants "$probe_body" "$tenant_token_second_family" "$(uuid_v7)"
assert_json '.code == "PLATFORM_AUTHORIZATION_DENIED"'

suspension_key="$(uuid_v7)"
request 503 POST "/api/v1/platform/tenants/$tenant_id/suspensions" '' \
  "$platform_token" "$suspension_key"
assert_json '.code == "TENANT_SUSPENSION_PENDING"'

# 首个有界批次返回 PENDING 时 Fence 已提交；此时既有 Token 和新签发都必须先于 Tenant 状态变更被阻断。
login_status="$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  --request POST \
  --header 'Content-Type: application/json' \
  --header 'X-SF-CSRF: 1' \
  --header 'Origin: https://console.saas.forge.test' \
  --header 'Sec-Fetch-Site: same-site' \
  --data-binary "$(jq -cn --arg email "$tenant_admin_email" --arg password "$tenant_password" \
    '{email:$email,password:$password,contextType:"TENANT"}')" \
  "$gateway_base/api/v1/auth/login")"
[[ "$login_status" == "403" ]]
assert_json '.code == "ACCESS_CONTEXT_UNAVAILABLE"'
request 401 POST /api/v1/platform/tenants "$probe_body" "$tenant_token" "$(uuid_v7)"
assert_json '.code == "ACCESS_TOKEN_INVALID"'
request 401 POST /api/v1/platform/tenants "$probe_body" "$tenant_token_second_family" "$(uuid_v7)"
assert_json '.code == "ACCESS_TOKEN_INVALID"'

# 一小时测试延迟保证旧进程不会抢跑；重启后新进程的首次调度从 PostgreSQL 租约与游标接管。
compose restart iam-service >/dev/null
wait_for_redis_revocation_ready
wait_for_revocation_worker_completion "$tenant_id"
compose restart tenant-access-service >/dev/null
wait_for_tenant_suspension_worker_completion "$tenant_id"
# 客户端丢弃首次 200，再以同一 Idempotency-Key 重放并比较持久化历史响应。
wait_for_suspension_completion_losing_response "$tenant_id" "$platform_token" "$suspension_key"
request 200 POST "/api/v1/platform/tenants/$tenant_id/suspensions" '' \
  "$platform_token" "$suspension_key"
assert_json '.status == "SUSPENDED"'
suspension_response="$(jq -cS . "$response_body")"

tenant_revocation_snapshot="$(postgres_value tenant_access_db \
  "SELECT tenant.tenant_status || ':' || workflow.workflow_status || ':'
       || workflow.attempt_count || ':' || workflow.revoked_family_count || ':'
       || workflow.revoked_jti_count || ':' || (SELECT count(*) FROM tenant_access_outbox_events
          WHERE event_snapshot->>'type' = 'com.saas.forge.tenant.suspended.v1'
            AND event_snapshot->'data'->>'tenantId' = '$tenant_id')
   FROM tenants tenant
   JOIN tenant_lifecycle_workflows workflow ON workflow.tenant_id = tenant.id
   WHERE tenant.id = '$tenant_id' AND workflow.lifecycle_action = 'SUSPEND'")"
iam_revocation_snapshot="$(postgres_value iam_db \
  "SELECT workflow.revocation_status || ':' || workflow.attempt_count || ':'
       || workflow.revoked_family_count || ':' || workflow.revoked_jti_count
   FROM iam_user_session_revocations workflow
   JOIN iam_revocation_fences fence USING (revocation_request_id)
   WHERE fence.target_type = 'TENANT' AND fence.target_id = '$tenant_id'")"
printf 'Tenant Access 撤销快照：%s；IAM 撤销快照：%s\n' \
  "$tenant_revocation_snapshot" "$iam_revocation_snapshot"
[[ "$tenant_revocation_snapshot" =~ ^SUSPENDED:COMPLETED:0:[2-9][0-9]*:[2-9][0-9]*:1$ ]]
[[ "$iam_revocation_snapshot" =~ ^COMPLETED:0:[2-9][0-9]*:[2-9][0-9]*$ ]]
tenant_revocation_counts="$(printf '%s' "$tenant_revocation_snapshot" | cut -d: -f4-5)"
iam_revocation_counts="$(printf '%s' "$iam_revocation_snapshot" | cut -d: -f3-4)"
[[ "$tenant_revocation_counts" == "$iam_revocation_counts" ]]

if [[ "${AUDIT_SUCCESS_FACTS_ONLY:-false}" != "true" ]]; then
tenant_count_before_fail_closed="$(compose exec -T postgres sh -eu -c '
  psql --username "$POSTGRES_USER" --dbname tenant_access_db --tuples-only --no-align \
    --command "SELECT count(*) FROM tenants"
' | tr -d '[:space:]')"
compose stop redis >/dev/null
request 503 POST /api/v1/platform/tenants "$probe_body" "$platform_token" "$(uuid_v7)"
assert_json '.code == "TOKEN_REVOCATION_STATUS_UNAVAILABLE"'
compose start redis >/dev/null
wait_for_redis_revocation_ready

verify_revocation_index_not_ready
wait_for_redis_revocation_ready

tenant_count_after_fail_closed="$(compose exec -T postgres sh -eu -c '
  psql --username "$POSTGRES_USER" --dbname tenant_access_db --tuples-only --no-align \
    --command "SELECT count(*) FROM tenants"
' | tr -d '[:space:]')"
[[ "$tenant_count_after_fail_closed" == "$tenant_count_before_fail_closed" ]]

resume_key="$(uuid_v7)"
wait_for_resume_after_redis_recovery "$tenant_id" "$platform_token" "$resume_key"
assert_json '.status == "ACTIVE"'
login "$tenant_admin_email" "$tenant_password" TENANT
assert_json '.contextState == "ACCESS_TOKEN_ISSUED" and (.accessToken | length > 100)'
tenant_token_after_resume="$(jq -r '.accessToken' "$response_body")"
request 403 POST /api/v1/platform/tenants "$probe_body" "$tenant_token_after_resume" "$(uuid_v7)"
assert_json '.code == "PLATFORM_AUTHORIZATION_DENIED"'
request 401 POST /api/v1/platform/tenants "$probe_body" "$tenant_token" "$(uuid_v7)"
assert_json '.code == "ACCESS_TOKEN_INVALID"'
request 401 POST /api/v1/platform/tenants "$probe_body" "$tenant_token_second_family" "$(uuid_v7)"
assert_json '.code == "ACCESS_TOKEN_INVALID"'
request 200 POST "/api/v1/platform/tenants/$tenant_id/suspensions" '' \
  "$platform_token" "$suspension_key"
[[ "$(jq -cS . "$response_body")" == "$suspension_response" ]]

compose exec -T postgres sh -eu -c '
  psql --username "$POSTGRES_USER" --dbname tenant_access_db --tuples-only --no-align \
    --set=tenant_id="'"$tenant_id"'" <<SQL | grep -qx t
      SELECT (SELECT tenant_status = '\''ACTIVE'\'' FROM tenants WHERE id = :'"'"'tenant_id'"'"')
         AND (SELECT count(*) = 2 FROM tenant_lifecycle_workflows WHERE tenant_id = :'"'"'tenant_id'"'"')
         AND (SELECT count(*) = 1 FROM tenant_access_outbox_events
              WHERE event_snapshot->>'\''type'\'' = '\''com.saas.forge.tenant.suspended.v1'\''
                AND event_snapshot->'\''data'\''->>'\''tenantId'\'' = :'"'"'tenant_id'"'"');
SQL
' >/dev/null

echo "[9/13] 验证无平台角色与错误 Scope"
request 403 POST /api/v1/platform/tenants \
  "$(jq -cn --arg expiresAt "$tenant_expires_at" \
    '{displayName:"Unauthorized Tenant",expiresAt:$expiresAt}')" \
  "$tenant_token_after_resume" "$(uuid_v7)"
cat "$response_body" >>"$business_response_log"
iam_client_id="$(<"$secret_directory/iam-client-id")"
iam_client_secret="$(<"$secret_directory/iam-client-secret")"
wrong_scope_status="$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  --user "$iam_client_id:$iam_client_secret" \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=iam:identity:write' \
  "$gateway_base/oauth2/token")"
printf '错误 Scope 响应状态：%s\n' "$wrong_scope_status"
[[ "$wrong_scope_status" == "403" ]]
cat "$response_body" >>"$business_response_log"

echo "[10/13] 验证额度耗尽、Tenant 到期与跨 Tenant RLS"
request 201 POST /api/v1/platform/plans \
  "$(jq -cn --arg id "$quota_definition_id" \
    '{code:"tenant-exhausted",displayName:"Tenant Exhausted",quotaLimits:[{quotaDefinitionId:$id,limit:1}]}')" \
  "$platform_token" "$(uuid_v7)"
exhausted_plan_id="$(jq -r '.id' "$response_body")"
cat "$response_body" >>"$business_response_log"
request 200 POST "/api/v1/platform/plans/$exhausted_plan_id/activations" '' \
  "$platform_token" "$(uuid_v7)"
exhausted_tenant_expires_at="$(ruby -rtime -e 'puts (Time.now.utc + 3600).iso8601')"
request 201 POST /api/v1/platform/tenants \
  "$(jq -cn --arg expiresAt "$exhausted_tenant_expires_at" \
    '{displayName:"Exhausted Quota Tenant",expiresAt:$expiresAt}')" \
  "$platform_token" "$(uuid_v7)"
exhausted_tenant_id="$(jq -r '.id' "$response_body")"
request 201 POST "/api/v1/platform/tenants/$exhausted_tenant_id/subscriptions" \
  "$(jq -cn --arg planId "$exhausted_plan_id" '{planId:$planId}')" \
  "$platform_token" "$(uuid_v7)"
# 通过正式服务凭据和 gRPC 占满合法正额度，不直接伪造 quota_usages。
quota_token_status="$(curl --silent --show-error --output "$response_body" --write-out '%{http_code}' \
  --user "$(<"$secret_directory/tenant-access-client-id"):$(<"$secret_directory/tenant-access-client-secret")" \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' --data-urlencode 'scope=entitlement:quota:write' \
  "$gateway_base/oauth2/token")"
[[ "$quota_token_status" == "200" ]]
quota_token="$(jq -r '.access_token' "$response_body")"
quota_grpc_port="$(compose port entitlement-service 9090 | sed 's/.*://')"
quota_probe() {
  SERVICE_ACCESS_TOKEN="$quota_token" "$repository_root/mvnw" --quiet -pl saas-forge-contracts/saas-forge-protobuf-contracts \
    -Denforcer.skip=true org.codehaus.mojo:exec-maven-plugin:java \
    -Dexec.mainClass=io.saas.forge.contracts.acceptance.QuotaCommandGrpcProbe \
    -Dexec.classpathScope=test -Dexec.args="$quota_grpc_port $exhausted_tenant_id $(uuid_v7) $1" \
    >>"$work_directory/quota-probe.log" 2>&1
}
quota_probe consume
request 409 POST "/api/v1/platform/tenants/$exhausted_tenant_id/administrator-initializations" \
  '{"administratorEmail":"exhausted-admin@saas.forge.test"}' \
  "$platform_token" "$(uuid_v7)"
assert_json '.code == "QUOTA_EXCEEDED"'
cat "$response_body" >>"$business_response_log"

quota_probe release
unset quota_token

expired_at="$(ruby -rtime -e 'puts (Time.now.utc + 4).iso8601')"
request 201 POST /api/v1/platform/tenants \
  "$(jq -cn --arg expiresAt "$expired_at" '{displayName:"Expiring Tenant",expiresAt:$expiresAt}')" \
  "$platform_token" "$(uuid_v7)"
expired_tenant_id="$(jq -r '.id' "$response_body")"
request 201 POST "/api/v1/platform/tenants/$expired_tenant_id/subscriptions" \
  "$(jq -cn --arg planId "$plan_id" '{planId:$planId}')" \
  "$platform_token" "$(uuid_v7)"
sleep 5
request 409 POST "/api/v1/platform/tenants/$expired_tenant_id/administrator-initializations" \
  '{"administratorEmail":"expired-admin@saas.forge.test"}' \
  "$platform_token" "$(uuid_v7)"
assert_json '.code == "TENANT_EXPIRY_REACHED"'
cat "$response_body" >>"$business_response_log"

compose exec -T postgres sh -eu -c '
  psql --username "$POSTGRES_USER" --dbname tenant_access_db --tuples-only --no-align \
    --set=visible="'"$tenant_id"'" --set=hidden="'"$exhausted_tenant_id"'" <<SQL | grep -qx t
      BEGIN;
      SET LOCAL ROLE tenant_access_app;
      SELECT set_config('\''app.tenant_id'\'', :'"'"'visible'"'"', true);
      SELECT count(*) = 1
      FROM tenants
      WHERE id IN (:'"'"'visible'"'"', :'"'"'hidden'"'"');
      ROLLBACK;
SQL
' >/dev/null
fi

echo "[11/13] 通过真实业务入口形成三类 Audit Record，并证明重复与 no-op"
second_tenant_expires_at="$(ruby -rtime -e 'puts (Time.now.utc + 86400).iso8601')"
request 201 POST /api/v1/platform/tenants \
  "$(jq -cn --arg expiresAt "$second_tenant_expires_at" \
    '{displayName:"Audit Switch Tenant",expiresAt:$expiresAt}')" \
  "$platform_token" "$(uuid_v7)"
second_tenant_id="$(jq -r '.id' "$response_body")"
request 201 POST "/api/v1/platform/tenants/$second_tenant_id/subscriptions" \
  "$(jq -cn --arg planId "$plan_id" '{planId:$planId}')" \
  "$platform_token" "$(uuid_v7)"
request 200 POST "/api/v1/platform/tenants/$second_tenant_id/administrator-initializations" \
  "$(jq -cn --arg email "$tenant_admin_email" '{administratorEmail:$email}')" \
  "$platform_token" "$(uuid_v7)"

third_tenant_expires_at="$(ruby -rtime -e 'puts (Time.now.utc + 86400).iso8601')"
request 201 POST /api/v1/platform/tenants \
  "$(jq -cn --arg expiresAt "$third_tenant_expires_at" \
    '{displayName:"Audit Current Tenant",expiresAt:$expiresAt}')" \
  "$platform_token" "$(uuid_v7)"
third_tenant_id="$(jq -r '.id' "$response_body")"
request 201 POST "/api/v1/platform/tenants/$third_tenant_id/subscriptions" \
  "$(jq -cn --arg planId "$plan_id" '{planId:$planId}')" \
  "$platform_token" "$(uuid_v7)"
request 200 POST "/api/v1/platform/tenants/$third_tenant_id/administrator-initializations" \
  "$(jq -cn --arg email "$tenant_admin_email" '{administratorEmail:$email}')" \
  "$platform_token" "$(uuid_v7)"

rm -f "$cookie_jar"
touch "$cookie_jar"
login "$tenant_admin_email" "$tenant_password" TENANT
assert_json '.contextState == "CONTEXT_SELECTION_REQUIRED" and (.memberships | length >= 2)'
target_membership_id="$(jq -r --arg tenantId "$second_tenant_id" \
  '.memberships[] | select(.tenantId == $tenantId) | .membershipId' "$response_body")"
current_membership_id="$(jq -r --arg tenantId "$third_tenant_id" \
  '.memberships[] | select(.tenantId == $tenantId) | .membershipId' "$response_body")"
[[ -n "$target_membership_id" && -n "$current_membership_id" ]]
request 200 POST /api/v1/auth/context-selections \
  "$(jq -cn --arg membershipId "$current_membership_id" '{membershipId:$membershipId}')"
request 204 POST /api/v1/auth/tenant-switches \
  "$(jq -cn --arg membershipId "$target_membership_id" '{membershipId:$membershipId}')" \
  '' "$(uuid_v7)"
request 200 POST /api/v1/auth/refresh '{"sessionSlot":"TENANT"}' '' "$(uuid_v7)"

wait_for_audit_condition \
  "EXISTS (SELECT 1 FROM audit_records WHERE action = 'SESSION_STARTED')" \
  "Session Started Audit Record"
wait_for_audit_condition \
  "EXISTS (SELECT 1 FROM audit_records WHERE action = 'TENANT_CREATED' AND tenant_id = '$second_tenant_id' AND actor_identity_id IS NOT NULL AND resource_type = 'TENANT' AND resource_id = '$second_tenant_id' AND result = 'SUCCESS' AND metadata = '{\"initialStatus\":\"PENDING\"}'::jsonb)" \
  "Tenant Created Audit Record"
wait_for_audit_condition \
  "EXISTS (SELECT 1 FROM audit_records WHERE action = 'TENANT_CONTEXT_SWITCHED' AND tenant_id = '$second_tenant_id' AND actor_identity_id IS NOT NULL AND resource_type = 'REFRESH_TOKEN_FAMILY' AND result = 'SUCCESS' AND metadata->>'targetMembershipId' = '$target_membership_id')" \
  "Tenant Context Switched Audit Record"

tenant_created_count_before="$(postgres_value audit_db \
  "SELECT count(*) FROM audit_records WHERE action = 'TENANT_CREATED' AND tenant_id = '$second_tenant_id'")"
republish_outbox_event tenant_access_db tenant_access_outbox_events \
  com.saas.forge.tenant.created.v1 "$second_tenant_id" \
  saas.forge.dev.tenant-access-service.events
sleep 3
tenant_created_count_after="$(postgres_value audit_db \
  "SELECT count(*) FROM audit_records WHERE action = 'TENANT_CREATED' AND tenant_id = '$second_tenant_id'")"
[[ "$tenant_created_count_before" == "1" && "$tenant_created_count_after" == "1" ]]

switch_count_before="$(postgres_value audit_db \
  "SELECT count(*) FROM audit_records WHERE action = 'TENANT_CONTEXT_SWITCHED' AND tenant_id = '$second_tenant_id'")"
request 204 POST /api/v1/auth/tenant-switches \
  "$(jq -cn --arg membershipId "$target_membership_id" '{membershipId:$membershipId}')" \
  '' "$(uuid_v7)"
sleep 3
switch_count_after="$(postgres_value audit_db \
  "SELECT count(*) FROM audit_records WHERE action = 'TENANT_CONTEXT_SWITCHED' AND tenant_id = '$second_tenant_id'")"
[[ "$switch_count_before" == "$switch_count_after" ]]

wait_for_audit_condition \
  "NOT EXISTS (SELECT 1 FROM audit_records WHERE source_type = 'com.saas.forge.tenant.suspended.v1') AND NOT EXISTS (SELECT 1 FROM audit_consumed_events WHERE source_type = 'com.saas.forge.tenant.suspended.v1')" \
  "同 Topic 合法 ignored 事件不持久化"

echo "[12/13] 验证永久隔离、瞬时重试耗尽、隔离发布、原 ID 重放、权限与 Readiness 恢复"
invalid_event_id="$(uuid_v7)"
invalid_identity_id="$(uuid_v7)"
invalid_family_id="$(uuid_v7)"
invalid_time="$(ruby -rtime -e 'puts Time.now.utc.iso8601(3)')"
invalid_sentinel="audit-invalid-password-$(openssl rand -hex 12)"
invalid_payload="$(jq -cn \
  --arg id "$invalid_event_id" --arg identityId "$invalid_identity_id" \
  --arg familyId "$invalid_family_id" --arg time "$invalid_time" \
  --arg password "$invalid_sentinel" \
  '{specversion:"1.0",id:$id,source:"urn:saas.forge:iam-service",
    type:"com.saas.forge.iam.session.started.v1",subject:$familyId,time:$time,
    datacontenttype:"application/json",
    dataschema:"https://saas.forge.io/contracts/events/iam-session-started.v1.schema.json",
    data:{familyId:$familyId,identityId:$identityId,purpose:"USER_TENANT",contextType:"TENANT",
      result:"ACCESS_TOKEN_ISSUED",occurredAt:$time,password:$password}}')"
publish_controlled_kafka_line saas.forge.dev.iam-service.events \
  "$invalid_identity_id|$invalid_payload"
wait_for_audit_condition \
  "EXISTS (SELECT 1 FROM audit_consumer_isolations WHERE event_id = '$invalid_event_id' AND status = 'REJECTED_NON_REPLAYABLE' AND safe_snapshot IS NULL AND attempt_count = 1)" \
  "永久错误首次失败即不可重放隔离"
[[ "$(postgres_value audit_db \
  "SELECT count(*) FROM audit_consumer_isolations WHERE safe_snapshot LIKE '%$invalid_sentinel%' OR diagnostic LIKE '%$invalid_sentinel%'")" == "0" ]]

transient_event_id="$(uuid_v7)"
transient_identity_id="$(uuid_v7)"
transient_family_id="$(uuid_v7)"
transient_time="$(ruby -rtime -e 'puts Time.now.utc.iso8601(3)')"
transient_payload="$(jq -cn \
  --arg id "$transient_event_id" --arg identityId "$transient_identity_id" \
  --arg familyId "$transient_family_id" --arg time "$transient_time" \
  '{specversion:"1.0",id:$id,source:"urn:saas.forge:iam-service",
    type:"com.saas.forge.iam.session.started.v1",subject:$familyId,time:$time,
    datacontenttype:"application/json",
    dataschema:"https://saas.forge.io/contracts/events/iam-session-started.v1.schema.json",
    data:{familyId:$familyId,identityId:$identityId,purpose:"USER_TENANT",contextType:"TENANT",
      result:"ACCESS_TOKEN_ISSUED",occurredAt:$time}}')"
cat >"$work_directory/audit-transient-trigger.sql" <<SQL
CREATE SEQUENCE audit_acceptance_failure_sequence;
CREATE FUNCTION audit_acceptance_fail_first_ten() RETURNS trigger LANGUAGE plpgsql AS \$\$
BEGIN
  IF NEW.source_event_id = '$transient_event_id'::uuid
     AND nextval('audit_acceptance_failure_sequence') <= 10 THEN
    RAISE EXCEPTION 'controlled transient audit failure';
  END IF;
  RETURN NEW;
END
\$\$;
CREATE TRIGGER audit_acceptance_fail_first_ten
BEFORE INSERT ON audit_records
FOR EACH ROW EXECUTE FUNCTION audit_acceptance_fail_first_ten();
SQL
compose exec -T postgres sh -eu -c \
  'psql --username "$POSTGRES_USER" --dbname audit_db --set ON_ERROR_STOP=1' \
  <"$work_directory/audit-transient-trigger.sql" >/dev/null
publish_controlled_kafka_line saas.forge.dev.iam-service.events \
  "$transient_identity_id|$transient_payload"
wait_for_audit_condition \
  "EXISTS (SELECT 1 FROM audit_consumer_isolations WHERE event_id = '$transient_event_id' AND status = 'OPEN' AND safe_snapshot IS NOT NULL AND attempt_count = 10)" \
  "瞬时失败十次后建立可重放隔离"
wait_for_audit_condition \
  "EXISTS (SELECT 1 FROM audit_isolation_deliveries delivery JOIN audit_consumer_isolations isolation USING (isolation_id) WHERE isolation.event_id = '$transient_event_id' AND delivery.published_at IS NOT NULL)" \
  "隔离 Topic 可靠投递"
[[ "$(postgres_value audit_db \
  "SELECT count(*) FROM audit_isolation_attempts WHERE event_id = '$transient_event_id' AND action = 'PROCESSING_FAILED'")" == "10" ]]
transient_isolation_id="$(postgres_value audit_db \
  "SELECT isolation_id FROM audit_consumer_isolations WHERE event_id = '$transient_event_id'")"

cat >"$work_directory/audit-transient-trigger-drop.sql" <<'SQL'
DROP TRIGGER audit_acceptance_fail_first_ten ON audit_records;
DROP FUNCTION audit_acceptance_fail_first_ten();
DROP SEQUENCE audit_acceptance_failure_sequence;
SQL
compose exec -T postgres sh -eu -c \
  'psql --username "$POSTGRES_USER" --dbname audit_db --set ON_ERROR_STOP=1' \
  <"$work_directory/audit-transient-trigger-drop.sql" >/dev/null
AUDIT_ISOLATION_REPLAY_ID="$transient_isolation_id" \
  compose --profile audit-isolation-replay run --rm audit-isolation-replay >/dev/null
wait_for_audit_condition \
  "EXISTS (SELECT 1 FROM audit_records WHERE source_event_id = '$transient_event_id') AND EXISTS (SELECT 1 FROM audit_consumer_isolations WHERE isolation_id = '$transient_isolation_id' AND status = 'RESOLVED')" \
  "原 Event ID 重放成功并完成处置"

compose exec -T postgres sh -eu -c '
  psql --username "$POSTGRES_USER" --dbname audit_db --set ON_ERROR_STOP=1 \
    --command "SET ROLE audit_app; SELECT count(*) FROM flyway_schema_history WHERE version = '\''5'\'';"
' >/dev/null
if compose exec -T postgres sh -eu -c '
  psql --username "$POSTGRES_USER" --dbname audit_db --set ON_ERROR_STOP=1 \
    --command "SET ROLE audit_app; UPDATE audit_records SET result = '\''SUCCESS'\'';"
' >/dev/null 2>&1; then
  echo "audit_app 不应拥有 audit_records UPDATE 权限" >&2
  exit 1
fi
if compose exec -T postgres sh -eu -c '
  psql --username "$POSTGRES_USER" --dbname audit_db --set ON_ERROR_STOP=1 \
    --command "SET ROLE audit_app; DELETE FROM audit_consumer_isolations;"
' >/dev/null 2>&1; then
  echo "audit_app 不应拥有隔离记录 DELETE 权限" >&2
  exit 1
fi

compose stop kafka >/dev/null
wait_for_audit_health readiness 503
wait_for_audit_health liveness 200
compose start kafka >/dev/null
wait_for_audit_health readiness 200
compose stop postgres >/dev/null
wait_for_audit_health readiness 503
wait_for_audit_health liveness 200
compose start postgres >/dev/null
wait_for_audit_health readiness 200

echo "[13/13] 扫描数据库、领域事件、业务 API 响应与应用日志的敏感明文"
assert_sensitive_material_absent

echo "Tenant 生命周期与 Audit 成功事实 Compose E2E 验收通过：真实业务入口、Kafka ACL、三类记录、失败隔离、重放及可恢复 Readiness 均已验证。"
