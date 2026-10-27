-- 仅用于本地开发：为每个服务创建独立 schema 与两个账号。
-- 迁移账号（<schema>_migrator）是 schema 所有者，供 Flyway 执行 DDL；
-- 运行账号（<schema>_app）只有数据读写权限且不拥有任何表，供应用连接。
-- 两者分离并且都不是超级用户、不带 BYPASSRLS，否则 RLS 兜底会失效（ADR-0001）。
-- 密码为本地开发固定值，任何共享环境都必须另行生成。

\set ON_ERROR_STOP on

CREATE OR REPLACE PROCEDURE pg_temp.create_service_schema(svc text)
LANGUAGE plpgsql
AS $$
DECLARE
    migrator text := svc || '_migrator';
    app      text := svc || '_app';
BEGIN
    EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS',
                   migrator, migrator);
    EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS',
                   app, app);

    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I, %I', current_database(), migrator, app);
    EXECUTE format('CREATE SCHEMA %I AUTHORIZATION %I', svc, migrator);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', svc, app);

    -- 表由迁移账号在之后的 Flyway 迁移中创建，用默认权限让运行账号自动获得数据读写权限，
    -- 而不需要每个迁移脚本手写 GRANT。
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I '
                   'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', migrator, svc, app);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I '
                   'GRANT USAGE, SELECT ON SEQUENCES TO %I', migrator, svc, app);

    -- 两个账号都只看得到本服务的 schema 与共享扩展，避免未限定 schema 的 SQL 意外落到别处。
    EXECUTE format('GRANT USAGE ON SCHEMA extensions TO %I, %I', migrator, app);
    EXECUTE format('ALTER ROLE %I SET search_path = %I, extensions', migrator, svc);
    EXECUTE format('ALTER ROLE %I SET search_path = %I, extensions', app, svc);
END;
$$;

-- PostgreSQL 15 起 public schema 默认已不允许 PUBLIC 建表，这里显式收回以免依赖版本行为。
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE forge FROM PUBLIC;

-- 扩展只能由超级用户安装，服务账号没有建 schema 与装扩展的权限；集中装在 extensions schema，各服务只获得 USAGE。
-- ltree 存放 Department 层级路径（ADR-0006）。
CREATE SCHEMA extensions;
CREATE EXTENSION ltree SCHEMA extensions;

CALL pg_temp.create_service_schema('iam');
CALL pg_temp.create_service_schema('platform');
CALL pg_temp.create_service_schema('demo');
