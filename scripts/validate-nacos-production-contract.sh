#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly contract_file="$repository_root/deploy/helm/nacos-production-contract.yaml"

ruby - "$contract_file" <<'RUBY'
require "yaml"

contract_file = ARGV.fetch(0)
contract = YAML.safe_load(File.read(contract_file), aliases: true)
nacos = contract.fetch("nacos")
server_address = nacos.fetch("serverAddress")
abort "#{contract_file}: Nacos 生产地址必须使用 HTTPS" unless server_address.start_with?("https://")
abort "#{contract_file}: Nacos namespace 必须显式设置为 prod" unless nacos.fetch("namespace") == "prod"

tls = nacos.fetch("tls")
abort "#{contract_file}: Nacos TLS 不得关闭" unless tls.fetch("enabled") == true
expected_option = "-Dcom.alibaba.nacos.client.naming.tls.enable=true"
unless tls.fetch("namingJavaToolOptions").split.include?(expected_option)
  abort "#{contract_file}: 必须启用 Nacos Naming TLS"
end

applications = %w[gateway iam-service tenant-access-service entitlement-service audit-service remote-delivery-service project-service]
credentials = nacos.fetch("workloadCredentials")
abort "#{contract_file}: 工作负载凭据必须一一对应应用" unless credentials.keys.sort == applications.sort
secret_names = applications.map { |application| credentials.fetch(application).fetch("existingSecretName") }
abort "#{contract_file}: 工作负载必须使用互不相同的外部 Secret" unless secret_names.all? { |name| name.is_a?(String) && !name.empty? } && secret_names.uniq.size == secret_names.size
RUBY

for application_file in \
  "$repository_root/gateway/src/main/resources/application.yaml" \
  "$repository_root/saas-forge-services/iam-service/src/main/resources/application.yaml" \
  "$repository_root/saas-forge-services/tenant-access-service/src/main/resources/application.yaml" \
  "$repository_root/saas-forge-services/entitlement-service/src/main/resources/application.yaml" \
  "$repository_root/saas-forge-services/audit-service/src/main/resources/application.yaml" \
  "$repository_root/saas-forge-services/remote-delivery-service/src/main/resources/application.yaml" \
  "$repository_root/examples/project-service/src/main/resources/application.yaml"; do
  if ! grep -Fq 'enabled: ${NACOS_TLS_ENABLED:false}' "$application_file"; then
    echo "$application_file: 缺少可由生产部署接口启用的 Nacos Config TLS" >&2
    exit 1
  fi
done
