-- 以管理员连接明确指定的目标库，psql -X -v service=orders
-- -v migrator_password=... -v app_password=... -f 此文件。
-- 服务名是数据库短名（demo），不必等于发现服务名（demo-service）。
\set ON_ERROR_STOP on
BEGIN;
CREATE OR REPLACE PROCEDURE pg_temp.provision_service(svc text, migration_secret text, runtime_secret text)
LANGUAGE plpgsql AS $$
DECLARE
    migrator text := svc || '_migrator';
    app text := svc || '_app';
    account text;
    owner_name text;
BEGIN
    -- PostgreSQL 标识符最多 63 字节，预留 _migrator 后缀，禁止静默截断造成账号碰撞。
    IF svc !~ '^[a-z][a-z0-9_]{0,53}$' OR svc IN ('public', 'extensions', 'information_schema')
       OR svc LIKE 'pg\_%' ESCAPE '\' THEN
        RAISE EXCEPTION 'Invalid service database name';
    END IF;
    IF coalesce(migration_secret, '') = '' OR coalesce(runtime_secret, '') = '' THEN
        RAISE EXCEPTION 'Both passwords are required';
    END IF;
    -- 同一服务的并行准备串行化；角色是集群级对象。
    PERFORM pg_advisory_xact_lock(hashtextextended('forge.provision.' || svc, 0));
    FOREACH account IN ARRAY ARRAY[migrator, app] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = account) THEN
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = account
                       AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication OR NOT rolcanlogin))
               OR EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
                          WHERE r.rolname = account) THEN
                RAISE EXCEPTION 'Existing role % is not a restricted service login', account;
            END IF;
        ELSE
            EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
                           account, CASE WHEN account = migrator THEN migration_secret ELSE runtime_secret END);
        END IF;
    END LOOP;
    SELECT pg_get_userbyid(nspowner) INTO owner_name FROM pg_namespace WHERE nspname = svc;
    IF owner_name IS NOT NULL AND owner_name <> migrator THEN
        RAISE EXCEPTION 'Schema % belongs to %, expected %', svc, owner_name, migrator;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class WHERE relowner = (SELECT oid FROM pg_roles WHERE rolname = app))
       OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = (SELECT oid FROM pg_roles WHERE rolname = app)) THEN
        RAISE EXCEPTION 'Runtime role % must not own relations or schemas', app;
    END IF;
    IF owner_name IS NULL THEN
        EXECUTE format('CREATE SCHEMA %I AUTHORIZATION %I', svc, migrator);
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I, %I', current_database(), migrator, app);
    EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC, %I', svc, app);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', svc, app);
    -- 同时覆盖已经迁移的表和以后由 Flyway 创建的表；不授予 TRUNCATE 或 DDL。
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM %I', svc, app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', svc, app);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', svc, app);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I '
                   'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', migrator, svc, app);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I '
                   'GRANT USAGE, SELECT ON SEQUENCES TO %I', migrator, svc, app);
    -- 与现有服务一致：extensions 只存共享扩展，不存业务数据；管理员提前安装。
    EXECUTE format('GRANT USAGE ON SCHEMA extensions TO %I, %I', migrator, app);
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, extensions', migrator, current_database(), svc);
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, extensions', app, current_database(), svc);
END;
$$;
CALL pg_temp.provision_service(:'service', :'migrator_password', :'app_password');
COMMIT;
