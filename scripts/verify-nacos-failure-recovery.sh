#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly compose_directory="$repository_root/deploy/acceptance"
readonly project_name="saas-forge-nacos-recovery-$$"
readonly work_directory="$(mktemp -d)"
readonly secret_directory="$work_directory/secrets"

export IAM_SERVICE_CLIENT_ID_FILE="$secret_directory/iam-client-id"
export IAM_SERVICE_CLIENT_SECRET_FILE="$secret_directory/iam-client-secret"
export TENANT_ACCESS_SERVICE_CLIENT_ID_FILE="$secret_directory/tenant-access-client-id"
export TENANT_ACCESS_SERVICE_CLIENT_SECRET_FILE="$secret_directory/tenant-access-client-secret"
export ENTITLEMENT_SERVICE_CLIENT_ID_FILE="$secret_directory/entitlement-client-id"
export ENTITLEMENT_SERVICE_CLIENT_SECRET_FILE="$secret_directory/entitlement-client-secret"

compose() {
  docker compose --ansi never --project-name "$project_name" \
    --file "$compose_directory/compose.yaml" \
    --file "$compose_directory/failure-recovery.override.yaml" \
    "$@"
}

cleanup() {
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$work_directory"
}

on_interrupt() {
  trap - EXIT
  cleanup
  exit 130
}

gateway_status() {
  compose run --rm --no-deps --quiet-pull failure-recovery-probe \
    --connect-timeout 1 --max-time 2 --silent --output /dev/null --write-out '%{http_code}' \
    http://gateway:8080/.well-known/jwks.json 2>/dev/null || true
}

wait_for_gateway_available() {
  local status
  for _ in $(seq 1 60); do
    status="$(gateway_status)"
    if [[ -n "$status" && "$status" != "000" && "$status" != "503" ]]; then
      return 0
    fi
    sleep 2
  done
  echo "Gateway 未能通过已知健康 IAM 实例转发请求" >&2
  return 1
}

wait_for_gateway_unavailable() {
  local status
  for _ in $(seq 1 60); do
    status="$(gateway_status)"
    if [[ "$status" == "503" ]]; then
      return 0
    fi
    sleep 2
  done
  echo "Gateway 在没有健康 IAM 实例时未返回 503" >&2
  return 1
}

trap cleanup EXIT
trap on_interrupt INT TERM

"$repository_root/saas-forge-services/iam-service/generate-service-client-secrets.sh" "$secret_directory" >/dev/null

COMPOSE_PROJECT_NAME="$project_name" \
LOCAL_COMPOSE_OVERRIDE_FILE="$compose_directory/failure-recovery.override.yaml" \
  bash "$repository_root/scripts/initialize-local-iam-signing-key.sh"

# 在独立步骤完成 Nacos 初始化，避免与 IAM/Gateway 启动并发。
compose up --detach --wait nacos
if ! compose run --rm --no-deps nacos-init; then
  compose logs --no-color --tail=80 nacos-init nacos >&2
  exit 1
fi

if [[ "${NACOS_RECOVERY_BUILD_IMAGES:-true}" == "true" ]]; then
  if ! compose up --detach --build gateway; then
    compose logs --no-color --tail=80 nacos-init nacos >&2
    exit 1
  fi
else
  if ! compose up --detach gateway; then
    compose logs --no-color --tail=80 nacos-init nacos >&2
    exit 1
  fi
fi
wait_for_gateway_available

compose stop iam-service
wait_for_gateway_unavailable

compose up --detach iam-service
wait_for_gateway_available

compose stop nacos
wait_for_gateway_available

if compose run --rm --no-deps iam-service; then
  echo "Nacos 控制面不可用时，新 IAM 实例不应成功启动" >&2
  exit 1
fi

echo "已验证：无健康实例返回 503；短暂 Nacos 故障期间既有 Gateway 继续转发；新 IAM 实例未 Ready。"
