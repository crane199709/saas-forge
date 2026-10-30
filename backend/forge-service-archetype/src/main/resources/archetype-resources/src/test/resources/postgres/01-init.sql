#set( $dollar = '$' )
#set( $hash = '#' )
-- 仅供本地 compose / 隔离测试；共享环境使用 lib/provision-service.sql 并传入独立密码。
\set ON_ERROR_STOP on

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec

-- ltree 由管理员安装到共享扩展 schema，业务账号只获得 USAGE。
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS ltree SCHEMA extensions;

\set service demo
\set migrator_password demo_migrator
\set app_password demo_app
\ir lib/provision-service.sql
