#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly nacos_root="$repository_root/deploy/nacos"
readonly applications=(gateway iam-service tenant-access-service entitlement-service audit-service remote-delivery-service project-service)
readonly environments=(dev test staging prod)

validate_refresh_boundaries() {
  ruby - "$repository_root" <<'RUBY'
repository_root = ARGV.fetch(0)
imports = {
  "gateway/src/main/resources/application.yaml" => "gateway.yaml",
  "saas-forge-services/iam-service/src/main/resources/application.yaml" => "iam-service.yaml",
  "saas-forge-services/tenant-access-service/src/main/resources/application.yaml" => "tenant-access-service.yaml",
  "saas-forge-services/entitlement-service/src/main/resources/application.yaml" => "entitlement-service.yaml",
  "saas-forge-services/audit-service/src/main/resources/application.yaml" => "audit-service.yaml",
  "saas-forge-services/remote-delivery-service/src/main/resources/application.yaml" => "remote-delivery-service.yaml",
  "examples/project-service/src/main/resources/application.yaml" => "project-service.yaml"
}.freeze

imports.each do |relative_path, resource|
  application_file = File.join(repository_root, relative_path)
  expected = "nacos:#{resource}?group=SAAS_FORGE&refreshEnabled=false"
  content = File.read(application_file)
  abort "#{application_file}: 必须以 refreshEnabled=false 导入应用专属 Nacos 资源" unless content.include?(expected)
  abort "#{application_file}: 禁止启用 Nacos 动态刷新" if content.include?("refreshEnabled=true")
end
RUBY
}

usage() {
  echo "用法: $0 [dev|test|staging|prod]" >&2
  exit 64
}

is_known_environment() {
  local candidate="$1"
  local environment
  for environment in "${environments[@]}"; do
    if [[ "$candidate" == "$environment" ]]; then
      return 0
    fi
  done
  return 1
}

validate_configuration_file() {
  local environment="$1"
  local application="$2"
  local configuration_file="$3"

  ruby - "$configuration_file" "$application" "$environment" <<'RUBY'
require "yaml"

configuration_file, application, environment = ARGV
components = {
  "gateway" => "gateway",
  "iam-service" => "iam",
  "tenant-access-service" => "tenant-access",
  "entitlement-service" => "entitlement",
  "audit-service" => "audit",
  "remote-delivery-service" => "remote-delivery",
  "project-service" => "example"
}.freeze

document = YAML.safe_load(File.read(configuration_file), aliases: true)
abort "#{configuration_file}: YAML 根节点必须是映射" unless document.is_a?(Hash)

component = components.fetch(application)
revision = document.dig("saas.forge", component, "configuration-revision")
if !revision.is_a?(String) || revision.empty?
  abort "#{configuration_file}: 必须声明 saas.forge.#{component}.configuration-revision"
end

forbidden_key_parts = %w[
  password passwd secret token credential privatekey accesskey clientsecret apikey keystore
].freeze
forbidden_nacos_paths = [
  %w[spring cloud nacos username],
  %w[spring cloud nacos password],
  %w[spring cloud nacos namespace],
  %w[spring cloud nacos server-addr],
  %w[spring config import]
].freeze

inspect_node = lambda do |value, path|
  if value.is_a?(Hash)
    value.each do |raw_key, child|
      key = raw_key.to_s
      normalized_key = key.downcase.delete("-_")
      if forbidden_key_parts.include?(normalized_key)
        abort "#{configuration_file}: 禁止将敏感键 #{(path + [key]).join(".")} 纳入 Nacos 清单"
      end
      child_path = path + [key]
      if forbidden_nacos_paths.include?(child_path)
        abort "#{configuration_file}: Nacos 连接参数必须留在部署环境变量或 Helm values，不得出现在清单中"
      end
      inspect_node.call(child, child_path)
    end
  elsif value.is_a?(Array)
    value.each_with_index { |child, index| inspect_node.call(child, path + [index.to_s]) }
  end
end
inspect_node.call(document, [])

puts "已校验 #{environment}/#{application}.yaml"
RUBY
}

validate_environment() {
  local environment="$1"
  local environment_directory="$nacos_root/$environment"
  local expected actual application configuration_file

  if [[ ! -d "$environment_directory" ]]; then
    echo "缺少 Nacos 环境目录: $environment_directory" >&2
    exit 1
  fi

  expected="$(printf '%s.yaml\n' "${applications[@]}" | sort)"
  actual="$(find "$environment_directory" -maxdepth 1 -type f -name '*.yaml' -exec basename {} \; | sort)"
  if [[ "$actual" != "$expected" ]]; then
    echo "$environment_directory 必须且只能包含已声明的应用专属配置资源" >&2
    diff -u <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") || true
    exit 1
  fi

  for application in "${applications[@]}"; do
    configuration_file="$environment_directory/$application.yaml"
    validate_configuration_file "$environment" "$application" "$configuration_file"
  done
}

if [[ $# -gt 1 ]]; then
  usage
fi

validate_refresh_boundaries

if [[ $# -eq 1 ]]; then
  is_known_environment "$1" || usage
  validate_environment "$1"
else
  for environment in "${environments[@]}"; do
    validate_environment "$environment"
  done
fi
