#!/bin/sh
set -eu
: "${FORGE_GATEWAY_URL:?Provide the internal gateway http(s) origin}"
# 防止环境变量成为 nginx 配置语句。
if ! printf '%s' "$FORGE_GATEWAY_URL" | grep -Eq '^https?://[a-zA-Z0-9.-]+(:[0-9]+)?$'; then
  echo 'FORGE_GATEWAY_URL must be an http(s) origin without a path' >&2; exit 1
fi
{
cat <<'CONF'
server {
  listen 8080;
  root /usr/share/nginx/html;
  location = /index.html { add_header Cache-Control "no-store" always; }
  location = /runtime-config.json { add_header Cache-Control "no-store" always; }
  location /assets/ {
    try_files $uri =404;
    add_header Cache-Control "public, max-age=31536000, immutable";
  }
  location / { try_files $uri $uri/ /index.html; add_header Cache-Control "no-store" always; }
CONF
printf '  location ~ ^/api(/|$) { rewrite ^/api(?:/(.*))?$ /$1 break; proxy_pass %s; proxy_set_header Host $http_host; proxy_set_header X-Forwarded-Proto $scheme; proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; }\n' "$FORGE_GATEWAY_URL"
printf '  location ~ ^/iam(/|$) { proxy_pass %s; proxy_set_header Host $http_host; proxy_set_header X-Forwarded-Proto $scheme; proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; }\n' "$FORGE_GATEWAY_URL"
printf '}\n'
} > /etc/nginx/conf.d/default.conf
# 仅暴露公共模式；内部网关地址不下发浏览器。
printf '{"apiMode":"same-origin"}\n' > /usr/share/nginx/html/runtime-config.json
