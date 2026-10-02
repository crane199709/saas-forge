#!/bin/sh
set -eu

api="http://nacos:8848/nacos"

login() {
  curl --fail --silent --show-error --request POST \
    --data-urlencode "username=$1" \
    --data-urlencode "password=$2" \
    "$api/v3/auth/user/login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

# 用户可能来自此前的初始化；无论创建结果如何都更新密码，保持 .env 为本地工作负载身份的唯一来源。
ensure_user() {
  curl --silent --show-error --request POST \
    --header "Authorization: Bearer $bootstrap_token" \
    --data-urlencode "username=$1" \
    --data-urlencode "password=$2" \
    "$api/v3/auth/user" >/dev/null || true

  curl --fail --silent --show-error --request PUT \
    --header "Authorization: Bearer $bootstrap_token" \
    --data-urlencode "username=$1" \
    --data-urlencode "newPassword=$2" \
    "$api/v3/auth/user" >/dev/null
}

# Nacos 3 首次启动没有默认管理员密码；此接口只会在还不存在全局管理员时成功。
curl --silent --show-error --request POST \
  --data-urlencode "password=$NACOS_BOOTSTRAP_PASSWORD" \
  "$api/v3/auth/user/admin" >/dev/null || true

bootstrap_token="$(login nacos "$NACOS_BOOTSTRAP_PASSWORD")"
test -n "$bootstrap_token"

for workload_username in \
  "$NACOS_IAM_USERNAME" \
  "$NACOS_TENANT_ACCESS_USERNAME" \
  "$NACOS_ENTITLEMENT_USERNAME" \
  "$NACOS_AUDIT_USERNAME" \
  "$NACOS_GATEWAY_USERNAME" \
  "$NACOS_PUBLISH_USERNAME"; do
  if [ "$workload_username" = "nacos" ]; then
    echo "Nacos 工作负载与配置发布身份不得使用 nacos 管理员账号" >&2
    exit 1
  fi
done

for workload_username in \
  "$NACOS_IAM_USERNAME" \
  "$NACOS_TENANT_ACCESS_USERNAME" \
  "$NACOS_ENTITLEMENT_USERNAME" \
  "$NACOS_AUDIT_USERNAME" \
  "$NACOS_GATEWAY_USERNAME" \
  "$NACOS_PUBLISH_USERNAME"; do
  username_occurrences=0
  for candidate_username in \
    "$NACOS_IAM_USERNAME" \
    "$NACOS_TENANT_ACCESS_USERNAME" \
    "$NACOS_ENTITLEMENT_USERNAME" \
    "$NACOS_AUDIT_USERNAME" \
    "$NACOS_GATEWAY_USERNAME" \
    "$NACOS_PUBLISH_USERNAME"; do
    if [ "$workload_username" = "$candidate_username" ]; then
      username_occurrences=$((username_occurrences + 1))
    fi
  done
  if [ "$username_occurrences" -ne 1 ]; then
    echo "Nacos 工作负载和配置发布身份必须彼此独立" >&2
    exit 1
  fi
done

curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
  --data-urlencode "namespaceName=$NACOS_NAMESPACE" \
  "$api/v3/admin/core/namespace" >/dev/null || true

ensure_user "$NACOS_IAM_USERNAME" "$NACOS_IAM_PASSWORD"
ensure_user "$NACOS_TENANT_ACCESS_USERNAME" "$NACOS_TENANT_ACCESS_PASSWORD"
ensure_user "$NACOS_ENTITLEMENT_USERNAME" "$NACOS_ENTITLEMENT_PASSWORD"
ensure_user "$NACOS_AUDIT_USERNAME" "$NACOS_AUDIT_PASSWORD"
ensure_user "$NACOS_GATEWAY_USERNAME" "$NACOS_GATEWAY_PASSWORD"
ensure_user "$NACOS_PUBLISH_USERNAME" "$NACOS_PUBLISH_PASSWORD"

# 新服务身份仅在显式提供成对部署凭据时创建，原有环境仍可单独初始化。
if [ -n "${NACOS_REMOTE_DELIVERY_USERNAME:-}" ] || [ -n "${NACOS_REMOTE_DELIVERY_PASSWORD:-}" ]; then
  test -n "${NACOS_REMOTE_DELIVERY_USERNAME:-}" && test -n "${NACOS_REMOTE_DELIVERY_PASSWORD:-}"
  test "$NACOS_REMOTE_DELIVERY_USERNAME" != nacos
  for existing in "$NACOS_IAM_USERNAME" "$NACOS_TENANT_ACCESS_USERNAME" "$NACOS_ENTITLEMENT_USERNAME" "$NACOS_AUDIT_USERNAME" "$NACOS_GATEWAY_USERNAME" "$NACOS_PUBLISH_USERNAME"; do
    test "$NACOS_REMOTE_DELIVERY_USERNAME" != "$existing"
  done
  ensure_user "$NACOS_REMOTE_DELIVERY_USERNAME" "$NACOS_REMOTE_DELIVERY_PASSWORD"
  curl --silent --show-error --request POST \
    --header "Authorization: Bearer $bootstrap_token" \
    --data-urlencode "username=$NACOS_REMOTE_DELIVERY_USERNAME" \
    --data-urlencode 'role=remote-delivery-service-dev' "$api/v3/auth/role" >/dev/null || true
  for permission in "dev:SAAS_FORGE:config/remote-delivery-service.yaml:r" "dev:DEFAULT_GROUP:naming/remote-delivery-service:w" "dev:DEFAULT_GROUP:naming/remote-delivery-service:r" "dev:DEFAULT_GROUP:naming/iam-service:r" "dev:DEFAULT_GROUP:naming/tenant-access-service:r"; do
    curl --silent --show-error --request POST \
      --header "Authorization: Bearer $bootstrap_token" \
      --data-urlencode 'role=remote-delivery-service-dev' \
      --data-urlencode "resource=${permission%:*}" --data-urlencode "action=${permission##*:}" "$api/v3/auth/permission" >/dev/null || true
  done
fi


curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "username=$NACOS_IAM_USERNAME" \
  --data-urlencode 'role=iam-service-dev' \
  "$api/v3/auth/role" >/dev/null || true

curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "username=$NACOS_TENANT_ACCESS_USERNAME" \
  --data-urlencode 'role=tenant-access-service-dev' \
  "$api/v3/auth/role" >/dev/null || true

curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "username=$NACOS_ENTITLEMENT_USERNAME" \
  --data-urlencode 'role=entitlement-service-dev' \
  "$api/v3/auth/role" >/dev/null || true

curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "username=$NACOS_AUDIT_USERNAME" \
  --data-urlencode 'role=audit-service-dev' \
  "$api/v3/auth/role" >/dev/null || true

curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "username=$NACOS_GATEWAY_USERNAME" \
  --data-urlencode 'role=gateway-dev' \
  "$api/v3/auth/role" >/dev/null || true

curl --silent --show-error --request POST \
  --header "Authorization: Bearer $bootstrap_token" \
  --data-urlencode "username=$NACOS_PUBLISH_USERNAME" \
  --data-urlencode 'role=config-publisher-dev' \
  "$api/v3/auth/role" >/dev/null || true

# IAM 注册自身，并为原生 Membership 调用读取 Tenant Access 实例；不授予其配置读取或发布权限。
for permission in \
  "dev:SAAS_FORGE:config/iam-service.yaml:r" \
  "dev:DEFAULT_GROUP:naming/iam-service:w" \
  "dev:DEFAULT_GROUP:naming/tenant-access-service:r"; do
  resource="${permission%:*}"
  action="${permission##*:}"
  curl --silent --show-error --request POST \
    --header "Authorization: Bearer $bootstrap_token" \
    --data-urlencode 'role=iam-service-dev' \
    --data-urlencode "resource=$resource" \
    --data-urlencode "action=$action" \
    "$api/v3/auth/permission" >/dev/null || true
done

# Tenant Access 发现 IAM 的认证协作与 Entitlement 的 Quota；其他权限保持应用隔离。
for permission in \
  "dev:SAAS_FORGE:config/tenant-access-service.yaml:r" \
  "dev:DEFAULT_GROUP:naming/tenant-access-service:w" \
  "dev:DEFAULT_GROUP:naming/tenant-access-service:r" \
  "dev:DEFAULT_GROUP:naming/iam-service:r" \
  "dev:DEFAULT_GROUP:naming/entitlement-service:r"; do
  resource="${permission%:*}"
  action="${permission##*:}"
  curl --silent --show-error --request POST \
    --header "Authorization: Bearer $bootstrap_token" \
    --data-urlencode 'role=tenant-access-service-dev' \
    --data-urlencode "resource=$resource" \
    --data-urlencode "action=$action" \
    "$api/v3/auth/permission" >/dev/null || true
done

for permission in \
  "dev:SAAS_FORGE:config/entitlement-service.yaml:r" \
  "dev:DEFAULT_GROUP:naming/entitlement-service:w" \
  "dev:DEFAULT_GROUP:naming/entitlement-service:r" \
  "dev:DEFAULT_GROUP:naming/iam-service:r" \
  "dev:DEFAULT_GROUP:naming/tenant-access-service:r"; do
  resource="${permission%:*}"
  action="${permission##*:}"
  curl --silent --show-error --request POST \
    --header "Authorization: Bearer $bootstrap_token" \
    --data-urlencode 'role=entitlement-service-dev' \
    --data-urlencode "resource=$resource" \
    --data-urlencode "action=$action" \
    "$api/v3/auth/permission" >/dev/null || true
done

# Audit 只读取自己的配置、注册和本机替换生命周期所需的自身实例；
# 它没有 Gateway 公开入口，因此 Gateway 不读取其注册信息。
for permission in \
  "dev:SAAS_FORGE:config/audit-service.yaml:r" \
  "dev:DEFAULT_GROUP:naming/audit-service:w" \
  "dev:DEFAULT_GROUP:naming/audit-service:r"; do
  resource="${permission%:*}"
  action="${permission##*:}"
  curl --silent --show-error --request POST \
    --header "Authorization: Bearer $bootstrap_token" \
    --data-urlencode 'role=audit-service-dev' \
    --data-urlencode "resource=$resource" \
    --data-urlencode "action=$action" \
    "$api/v3/auth/permission" >/dev/null || true
done

# Gateway 只读取自己的配置、注册和本机替换生命周期所需的自身实例，
# 并读取公开路由所属服务的健康实例；它不能修改这些服务的注册信息。
# gateway-discovery-permissions: begin
for permission in \
  "dev:SAAS_FORGE:config/gateway.yaml:r" \
  "dev:DEFAULT_GROUP:naming/gateway:w" \
  "dev:DEFAULT_GROUP:naming/gateway:r" \
  "dev:DEFAULT_GROUP:naming/iam-service:r" \
  "dev:DEFAULT_GROUP:naming/tenant-access-service:r" \
  "dev:DEFAULT_GROUP:naming/entitlement-service:r" \
  "dev:DEFAULT_GROUP:naming/remote-delivery-service:r"; do
  resource="${permission%:*}"
  action="${permission##*:}"
  curl --silent --show-error --request POST \
    --header "Authorization: Bearer $bootstrap_token" \
    --data-urlencode 'role=gateway-dev' \
    --data-urlencode "resource=$resource" \
    --data-urlencode "action=$action" \
    "$api/v3/auth/permission" >/dev/null || true
done
# gateway-discovery-permissions: end

# 配置发布身份仅能写入当前环境的六份受控资源；它没有服务注册或发现权限。
for application in gateway iam-service tenant-access-service entitlement-service audit-service remote-delivery-service; do
  curl --silent --show-error --request POST \
    --header "Authorization: Bearer $bootstrap_token" \
    --data-urlencode 'role=config-publisher-dev' \
    --data-urlencode "resource=dev:SAAS_FORGE:config/$application.yaml" \
    --data-urlencode 'action=w' \
    "$api/v3/auth/permission" >/dev/null || true
done

publisher_token="$(login "$NACOS_PUBLISH_USERNAME" "$NACOS_PUBLISH_PASSWORD")"
test -n "$publisher_token"

curl --fail --silent --show-error --request POST \
  --header "Authorization: Bearer $publisher_token" \
  --data-urlencode 'dataId=remote-delivery-service.yaml' \
  --data-urlencode 'groupName=SAAS_FORGE' --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
  --data-urlencode 'type=yaml' --data-urlencode 'content@/config/remote-delivery-service.yaml' \
  "$api/v3/admin/cs/config" | grep -q '"code":0'


curl --fail --silent --show-error --request POST \
  --header "Authorization: Bearer $publisher_token" \
  --data-urlencode 'dataId=iam-service.yaml' \
  --data-urlencode 'groupName=SAAS_FORGE' \
  --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
  --data-urlencode 'type=yaml' \
  --data-urlencode 'content@/config/iam-service.yaml' \
  "$api/v3/admin/cs/config" | grep -q '"code":0'

curl --fail --silent --show-error --request POST \
  --header "Authorization: Bearer $publisher_token" \
  --data-urlencode 'dataId=tenant-access-service.yaml' \
  --data-urlencode 'groupName=SAAS_FORGE' \
  --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
  --data-urlencode 'type=yaml' \
  --data-urlencode 'content@/config/tenant-access-service.yaml' \
  "$api/v3/admin/cs/config" | grep -q '"code":0'

curl --fail --silent --show-error --request POST \
  --header "Authorization: Bearer $publisher_token" \
  --data-urlencode 'dataId=entitlement-service.yaml' \
  --data-urlencode 'groupName=SAAS_FORGE' \
  --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
  --data-urlencode 'type=yaml' \
  --data-urlencode 'content@/config/entitlement-service.yaml' \
  "$api/v3/admin/cs/config" | grep -q '"code":0'

curl --fail --silent --show-error --request POST \
  --header "Authorization: Bearer $publisher_token" \
  --data-urlencode 'dataId=audit-service.yaml' \
  --data-urlencode 'groupName=SAAS_FORGE' \
  --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
  --data-urlencode 'type=yaml' \
  --data-urlencode 'content@/config/audit-service.yaml' \
  "$api/v3/admin/cs/config" | grep -q '"code":0'

curl --fail --silent --show-error --request POST \
  --header "Authorization: Bearer $publisher_token" \
  --data-urlencode 'dataId=gateway.yaml' \
  --data-urlencode 'groupName=SAAS_FORGE' \
  --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
  --data-urlencode 'type=yaml' \
  --data-urlencode 'content@/config/gateway.yaml' \
  "$api/v3/admin/cs/config" | grep -q '"code":0'

# 发布成功不代表各工作负载的 ACL 已可见；空配置会使服务在缺失安全属性时启动失败。
workload_config_is_readable() {
  username="$1"
  password="$2"
  application="$3"
  workload_response_code=unavailable
  workload_token="$(login "$username" "$password")" || return 1
  workload_config="$(curl --silent --show-error --get \
    --header "Authorization: Bearer $workload_token" \
    --data-urlencode "dataId=$application.yaml" \
    --data-urlencode 'groupName=SAAS_FORGE' \
    --data-urlencode "namespaceId=$NACOS_NAMESPACE" \
    "$api/v3/client/cs/config")" || return 1
  # 只记录响应码；响应正文可能携带配置，登录 Token 与凭据不得进入诊断。
  workload_response_code="$(printf '%s' "$workload_config" | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
  workload_response_code="${workload_response_code:-invalid-response}"
  printf '%s' "$workload_config" | grep -Eq '"code"[[:space:]]*:[[:space:]]*0' \
    && printf '%s' "$workload_config" | grep -Eq '"content"[[:space:]]*:[[:space:]]*"[^\"]'
}

wait_for_workload_config() {
  application="$1"
  username="$2"
  password="$3"
  attempt=1
  while [ "$attempt" -le 30 ]; do
    if workload_config_is_readable "$username" "$password" "$application"; then
      return 0
    fi
    sleep 1
    attempt=$((attempt + 1))
  done
  echo "$application 工作负载未能在 30 次尝试内读取非空 Nacos 配置，最近响应码：$workload_response_code" >&2
  exit 1
}

wait_for_workload_config iam-service "$NACOS_IAM_USERNAME" "$NACOS_IAM_PASSWORD"
wait_for_workload_config tenant-access-service "$NACOS_TENANT_ACCESS_USERNAME" "$NACOS_TENANT_ACCESS_PASSWORD"
wait_for_workload_config entitlement-service "$NACOS_ENTITLEMENT_USERNAME" "$NACOS_ENTITLEMENT_PASSWORD"
wait_for_workload_config audit-service "$NACOS_AUDIT_USERNAME" "$NACOS_AUDIT_PASSWORD"
wait_for_workload_config gateway "$NACOS_GATEWAY_USERNAME" "$NACOS_GATEWAY_PASSWORD"

if [ -n "${NACOS_REMOTE_DELIVERY_USERNAME:-}" ]; then
  wait_for_workload_config remote-delivery-service "$NACOS_REMOTE_DELIVERY_USERNAME" "$NACOS_REMOTE_DELIVERY_PASSWORD"
fi
