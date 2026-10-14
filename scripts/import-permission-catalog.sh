#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || -z "${PERMISSION_CATALOG_TOPIC:-}" ]]; then
  echo 'usage: PERMISSION_CATALOG_TOPIC=saas.forge.<env>.tenant-access-service.events scripts/import-permission-catalog.sh manifest.json' >&2
  exit 2
fi

# psql must connect with the tenant_access_migrator credential supplied by the controlled release job.
manifest=$(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1])), separators=(",", ":")))' "$1")
artifact_sha256=$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1")
psql -X --set=ON_ERROR_STOP=1 --single-transaction \
  --set="manifest=$manifest" --set="artifact_sha256=$artifact_sha256" \
  --set="topic=$PERMISSION_CATALOG_TOPIC" \
  --file="$(dirname "$0")/import-permission-catalog.sql"
