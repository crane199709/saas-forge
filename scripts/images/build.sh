#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
version=$(python3 -c 'import json; print(json.load(open("frontend/package.json"))["version"])')
prefix=${IMAGE_PREFIX:-forge}
tag=${IMAGE_TAG:-$version}
# Boot 使用 DOCKER_HOST；兼容 Docker Desktop 未建立 /var/run/docker.sock 的机器。
if [[ -z "${DOCKER_HOST:-}" ]]; then
  export DOCKER_HOST
  DOCKER_HOST=$(docker context inspect --format '{{.Endpoints.docker.Host}}')
fi
mvn -B -f backend/pom.xml -Pimages clean install -DskipTests
for service in forge-gateway forge-iam forge-platform examples/demo-service; do
  name=${service##*/}
  mvn -B -f "backend/$service/pom.xml" -Pimages spring-boot:build-image \
    -DskipTests -Dimage.source="${IMAGE_SOURCE:-}" -Dspring-boot.build-image.imageName="$prefix/$name:$tag"
done
(cd frontend && pnpm --workspace-concurrency=1 --filter @forge/tenant-app --filter @forge/platform-console build)
for app in tenant-app platform-console; do
  docker build -f deploy/images/Shell.Dockerfile --build-arg "SHELL_APP=$app" --label "org.opencontainers.image.source=${IMAGE_SOURCE:-}" -t "$prefix/$app:$tag" .
done
