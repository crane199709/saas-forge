#!/bin/sh
# 把 /nacos/config/*.yaml 发布到 Nacos（public 命名空间、DEFAULT_GROUP，dataId 即文件名）。
# Nacos 3 的配置写入只开放在需要鉴权的管理 API 上，因此先初始化管理员密码再登录取 token。
set -eu

NACOS_ADDR="${NACOS_ADDR:-http://localhost:8848}"
NACOS_ADMIN_PASSWORD="${NACOS_ADMIN_PASSWORD:-nacos}"
CONFIG_DIR="${CONFIG_DIR:-/nacos/config}"

# 首次启动时管理员没有密码；已初始化过会返回错误，忽略即可。
curl -s -o /dev/null -X POST "$NACOS_ADDR/nacos/v3/auth/user/admin" \
  --data-urlencode "password=$NACOS_ADMIN_PASSWORD" || true

login=$(curl -sf -X POST "$NACOS_ADDR/nacos/v3/auth/user/login" \
  --data-urlencode "username=nacos" --data-urlencode "password=$NACOS_ADMIN_PASSWORD")
token=$(echo "$login" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

for file in "$CONFIG_DIR"/*.yaml; do
  data_id=$(basename "$file")
  result=$(curl -sf -X POST "$NACOS_ADDR/nacos/v3/admin/cs/config" \
    -H "accessToken: $token" \
    --data-urlencode "dataId=$data_id" \
    --data-urlencode "groupName=DEFAULT_GROUP" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content@$file")
  echo "published $data_id: $result"
done
