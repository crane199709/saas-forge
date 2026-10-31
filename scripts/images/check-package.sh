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
    repository=$(jq -r '.repository.full_name' "$response")
    printf '%s: visibility=%s repository=%s\n' "$package" "$visibility" "$repository"
    if [[ "$visibility" != private || "$repository" != "$GITHUB_REPOSITORY" ]]; then
      printf 'Package metadata mismatch for %s: expected visibility=private repository=%s; actual visibility=%s repository=%s\n' \
        "$package" "$GITHUB_REPOSITORY" "$visibility" "$repository" >&2
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
