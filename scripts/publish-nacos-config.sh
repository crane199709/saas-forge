#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly applications=(gateway iam-service tenant-access-service entitlement-service audit-service remote-delivery-service project-service)

usage() {
  echo "用法: $0 <dev|test|staging|prod>" >&2
  exit 64
}

[[ $# -eq 1 ]] || usage
readonly environment="$1"
case "$environment" in
  dev|test|staging|prod) ;;
  *) usage ;;
esac

: "${NACOS_SERVER_ADDR:?NACOS_SERVER_ADDR is required}"
: "${NACOS_PUBLISH_USERNAME:?NACOS_PUBLISH_USERNAME is required}"
: "${NACOS_PUBLISH_PASSWORD:?NACOS_PUBLISH_PASSWORD is required}"

bash "$repository_root/scripts/validate-nacos-config.sh" "$environment"

nacos_address="$NACOS_SERVER_ADDR"
case "$nacos_address" in
  http://*|https://*) ;;
  *) nacos_address="http://$nacos_address" ;;
esac
nacos_api="${nacos_address%/}"
case "$nacos_api" in
  */nacos) ;;
  *) nacos_api="$nacos_api/nacos" ;;
esac

login_response="$(curl --fail --silent --show-error --request POST \
  --data-urlencode "username=$NACOS_PUBLISH_USERNAME" \
  --data-urlencode "password=$NACOS_PUBLISH_PASSWORD" \
  "$nacos_api/v3/auth/user/login")"
publisher_token="$(printf '%s' "$login_response" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
if [[ -z "$publisher_token" ]]; then
  echo "Nacos 发布身份登录未返回 accessToken" >&2
  exit 1
fi

readonly release_reference="Git ${GITHUB_SHA:-local}; workflow ${GITHUB_WORKFLOW:-manual}; run ${GITHUB_RUN_ID:-local}"
for application in "${applications[@]}"; do
  configuration_file="$repository_root/deploy/nacos/$environment/$application.yaml"
  response="$(curl --fail --silent --show-error --request POST \
    --header "Authorization: Bearer $publisher_token" \
    --data-urlencode "dataId=$application.yaml" \
    --data-urlencode 'groupName=SAAS_FORGE' \
    --data-urlencode "namespaceId=$environment" \
    --data-urlencode 'type=yaml' \
    --data-urlencode "srcUser=github-actions" \
    --data-urlencode "desc=$release_reference" \
    --data-urlencode "content@$configuration_file" \
    "$nacos_api/v3/admin/cs/config")"
  if ! printf '%s' "$response" | grep -Eq '"code"[[:space:]]*:[[:space:]]*0'; then
    echo "Nacos 未确认发布 $environment/$application.yaml" >&2
    exit 1
  fi

  checksum="$(shasum -a 256 "$configuration_file" | awk '{print $1}')"
  echo "已发布 $environment/$application.yaml sha256=$checksum"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    echo "- \`$environment/$application.yaml\`: \`$checksum\`" >> "$GITHUB_STEP_SUMMARY"
  fi
done
