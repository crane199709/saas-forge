#!/usr/bin/env bash
set -euo pipefail

require_environment_variable() {
    local variable_name="$1"
    if [[ -z "${!variable_name:-}" ]]; then
        echo "缺少必填环境变量: ${variable_name}" >&2
        exit 1
    fi
}

for variable_name in \
    IAM_MIGRATOR_PASSWORD IAM_APP_PASSWORD \
    TENANT_ACCESS_MIGRATOR_PASSWORD TENANT_ACCESS_APP_PASSWORD \
    ENTITLEMENT_MIGRATOR_PASSWORD ENTITLEMENT_APP_PASSWORD \
    AUDIT_MIGRATOR_PASSWORD AUDIT_APP_PASSWORD; do
    require_environment_variable "$variable_name"
done

create_role() {
    local role_name="$1"
    local role_password="$2"

    psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
        --set role_name="$role_name" --set role_password="$role_password" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L',
    :'role_name',
    :'role_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role_name')
\gexec
SQL
}

create_database() {
    local database_name="$1"

    psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
        --set database_name="$database_name" <<'SQL'
SELECT format('CREATE DATABASE %I', :'database_name')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'database_name')
\gexec
SQL
}

configure_database_permissions() {
    local database_name="$1"
    local migrator_role="$2"
    local app_role="$3"

    psql --username "$POSTGRES_USER" --dbname "$database_name" --set ON_ERROR_STOP=1 \
        --set database_name="$database_name" --set migrator_role="$migrator_role" --set app_role="$app_role" <<'SQL'
REVOKE ALL ON DATABASE :"database_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database_name" TO :"migrator_role", :"app_role";
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO :"migrator_role";
GRANT USAGE ON SCHEMA public TO :"app_role";
SQL
}

create_role iam_migrator "$IAM_MIGRATOR_PASSWORD"
create_role iam_app "$IAM_APP_PASSWORD"
create_role tenant_access_migrator "$TENANT_ACCESS_MIGRATOR_PASSWORD"
create_role tenant_access_app "$TENANT_ACCESS_APP_PASSWORD"
create_role entitlement_migrator "$ENTITLEMENT_MIGRATOR_PASSWORD"
create_role entitlement_app "$ENTITLEMENT_APP_PASSWORD"
create_role audit_migrator "$AUDIT_MIGRATOR_PASSWORD"
create_role audit_app "$AUDIT_APP_PASSWORD"

create_database iam_db
create_database tenant_access_db
create_database entitlement_db
create_database audit_db

configure_database_permissions iam_db iam_migrator iam_app
configure_database_permissions tenant_access_db tenant_access_migrator tenant_access_app
configure_database_permissions entitlement_db entitlement_migrator entitlement_app
configure_database_permissions audit_db audit_migrator audit_app

# 仅为新数据边界准备新增服务；本脚本仍由 PostgreSQL 首次初始化运行。
if [[ -n "${REMOTE_DELIVERY_MIGRATOR_PASSWORD:-}" || -n "${REMOTE_DELIVERY_APP_PASSWORD:-}" ]]; then
    require_environment_variable REMOTE_DELIVERY_MIGRATOR_PASSWORD
    require_environment_variable REMOTE_DELIVERY_APP_PASSWORD
    create_role remote_delivery_migrator "$REMOTE_DELIVERY_MIGRATOR_PASSWORD"
    create_role remote_delivery_app "$REMOTE_DELIVERY_APP_PASSWORD"
    create_database remote_delivery_db
    configure_database_permissions remote_delivery_db remote_delivery_migrator remote_delivery_app
fi

if [[ -n "${PROJECT_MIGRATOR_PASSWORD:-}" || -n "${PROJECT_APP_PASSWORD:-}" ]]; then
    require_environment_variable PROJECT_MIGRATOR_PASSWORD
    require_environment_variable PROJECT_APP_PASSWORD
    create_role project_migrator "$PROJECT_MIGRATOR_PASSWORD"
    create_role project_app "$PROJECT_APP_PASSWORD"
    create_database project_db
    configure_database_permissions project_db project_migrator project_app
fi
