#!/usr/bin/env bash
set -euo pipefail
name=${1:?image name is required}
owner_path=users
if [[ "${OWNER_TYPE:-}" == Organization ]]; then owner_path=orgs; fi
package=${IMAGE_PREFIX#ghcr.io/*/}/$name
encoded=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$package")
package_api="/$owner_path/$GITHUB_REPOSITORY_OWNER/packages/container/$encoded"
response=$(mktemp)
trap 'rm -f "$response"' EXIT
status=$(curl --silent --show-error --output "$response" --write-out '%{http_code}' \
  -H "Authorization: Bearer $GH_TOKEN" "$GITHUB_API_URL$package_api")
case "$status" in
  200)
    visibility=$(jq -r '.visibility' "$response")
    actual_name=$(jq -r '.name' "$response")
    owner=$(jq -r '.owner.login' "$response" | tr '[:upper:]' '[:lower:]')
    expected_owner=$(printf '%s' "$GITHUB_REPOSITORY_OWNER" | tr '[:upper:]' '[:lower:]')
    repository=$(jq -r '.repository.full_name' "$response")
    printf '%s: visibility=%s name=%s owner=%s repository=%s\n' "$package" "$visibility" "$actual_name" "$owner" "$repository"
    if [[ "$visibility" != private || "$actual_name" != "$package" || "$owner" != "$expected_owner" ]]; then
      printf 'Package identity mismatch: expected visibility=private name=%s owner=%s; actual visibility=%s name=%s owner=%s\n' \
        "$package" "$expected_owner" "$visibility" "$actual_name" "$owner" >&2
      exit 1
    fi
    if [[ "$repository" == null ]]; then
      # Container 包响应可省略 repository；source 标签和令牌实际推拉分别验收源码标记与访问权。
      printf '%s: API did not return repository; source label and token push/pull are checked separately\n' "$package"
    elif [[ "$repository" != "$GITHUB_REPOSITORY" ]]; then
      printf 'Package repository mismatch for %s: expected=%s actual=%s\n' "$package" "$GITHUB_REPOSITORY" "$repository" >&2
      exit 1
    fi
    ;;
  404)
    if [[ "${2:-}" == allow-missing ]]; then
      printf '%s: HTTP 404; package absent or inaccessible, allowing first publication\n' "$package"
    else
      printf '%s: expected published package metadata, actual HTTP 404\n' "$package" >&2
      exit 1
    fi
    ;;
  *) printf '%s: cannot verify package access; HTTP %s\n' "$package" "$status" >&2; exit 1 ;;
esac
