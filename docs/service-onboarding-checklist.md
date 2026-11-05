# Business Service 平台侧接入清单

适用于工单 [03](../.scratch/sdk-delivery/issues/03-db-provisioning-and-onboarding-checklist.md)。按顺序完成；共享环境由对应管理员执行，先确认环境与数据库目标。本地固定密码、令牌不可用于共享环境。

先记录四个名称：数据库短名（例 `orders`）、`spring.application.name`（例 `orders-service`）、网关前缀（例 `/orders`）、可选 Sub-App 编码（例 `orders`）。它们用途不同，不要求相同；数据库短名仅允许小写字母开头及小写字母、数字、下划线，最长 54 字符。

## 1. 数据库与双账号

- [ ] **做什么**：在目标 PostgreSQL 数据库创建服务 schema、迁移账号 `<短名>_migrator` 与运行账号 `<短名>_app`。两个账号均非超级用户、无 BYPASSRLS/建库/建角色权限；迁移账号拥有 schema，运行账号只有该 schema 的数据读写与序列使用权限。
- [ ] **在哪里配**：使用 [provision-service.sql](../deploy/postgres/init/lib/provision-service.sql)，需要 `psql` 和管理员连接。数据库已存在；管理员应先在 `extensions` schema 安装共享 `ltree` 扩展。共享扩展只有 USAGE，是业务 schema 隔离之外的明确例外。数据库必须提前收回 PUBLIC 的 CREATE/临时对象等不必要权限，业务 schema 不向 PUBLIC 授权；脚本不修改其他服务或全库的 PUBLIC 权限。

```bash
# 明确填写目标，不依赖 psql 的默认数据库。管理员密码用 PGPASSFILE 或交互输入。
export PGHOST='<数据库主机>' PGPORT='5432' PGDATABASE='<目标数据库>' PGUSER='<管理员>'
psql -X -c 'SELECT current_database(), current_user, inet_server_addr(), inet_server_port();'
# 下列变量由本环境密钥管理提供；不要启用 shell xtrace 或把真实密码写入版本库。
psql -X -v service=orders \
  -v migrator_password="$ORDERS_MIGRATOR_PASSWORD" \
  -v app_password="$ORDERS_APP_PASSWORD" \
  -f deploy/postgres/init/lib/provision-service.sql
```

参数通过 psql 的 SQL 字面量引用传入，可包含引号。命令行参数可能被同机用户读取，须在受控运维终端执行。脚本事务执行，失败整体回滚；已存在的安全账号、schema 和业务数据保留，**不会轮换已有密码**。同名高权限角色、角色成员关系、错误 schema 所有者或运行账号已有对象所有权会失败，须由 DBA 先排查，不能靠脚本接管已有资源。脚本仅为指定服务授予权限，不负责清理历史的跨 schema 授权；已有库须由 DBA 审计 PUBLIC、角色和对象授权。

服务配置（密码通过该服务自己的密钥注入）：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
    username: orders_app
    password: ${ORDERS_APP_PASSWORD}
  flyway:
    user: orders_migrator
    password: ${ORDERS_MIGRATOR_PASSWORD}
    schemas: orders
    default-schema: orders
    create-schemas: false
```

- [ ] **怎么确认**：连续运行两次均成功；核对 `pg_roles` 的 `rolsuper`、`rolbypassrls` 均为 false，`pg_namespace` 所有者为迁移账号，表所有者不是运行账号。启动服务后 Flyway 迁移成功，`flyway_schema_history.installed_by` 为迁移账号。运行账号建表应失败，不能访问其他服务业务 schema。
- [ ] 迁移中对租户表设置 `ENABLE ROW LEVEL SECURITY`、`FORCE ROW LEVEL SECURITY` 和使用 `app.tenant_id` 的策略。**准备脚本不替服务生成 RLS 策略**。沿用 `TenantIsolationTest`：以运行账号直接执行 SQL，租户 A 看不到 B、更新/删除 B 影响 0 行、插入 B 的数据报 SQLSTATE `42501`；未设置租户时看不到数据。只能在隔离验收库准备这些样本。

本地 compose 和主链路 E2E 都挂载 `deploy/postgres/init`，入口 `01-service-schemas.sql` 调用同一参数化脚本准备 iam/platform/demo。Testcontainers 复制同一目录。PostgreSQL 镜像只对空数据目录执行初始化；已有卷不会自动重跑。不要为接入新服务删除已有开发库。

## 2. IAM Resource Manifest 上报令牌

- [ ] **做什么**：为新服务生成独立令牌，以准确的 `spring.application.name` 登记到 IAM；同时交给业务服务。
- [ ] **在哪里配**：IAM 的 `forge.iam.manifest.tokens.<服务名>` 与服务的 `forge.manifest.token` 使用同一密钥。服务配置 `forge.manifest.iam-uri` 为 IAM 内部直连地址、`forge.manifest.version` 为清单版本。参考 `backend/forge-iam/src/main/resources/application.yml` 与 demo-service 配置；使用受控部署配置或服务实际导入的 Nacos 配置覆盖，不能只创建一个未被导入的 dataId。重启相关服务。
- [ ] **怎么确认**：服务启动后向 IAM 的 `POST /internal/manifests` 上报成功，平台套餐的权限目录出现该服务的权限码；错误令牌上报被拒。该内部接口不能经网关访问。权限、菜单、角色模板或 Data Resource 改动时递增 version，否则不能按新内容更新。

## 3. RocketMQ 与租户开通

- [ ] **做什么**：列出服务生产、消费的 Topic，确认共享 Topic `forge-tenant`、`forge-app`、`forge-audit`，再创建服务专属 Topic（demo 的例子是 `demo-order`）。
- [ ] **在哪里配**：方式与 [create-topics.sh](../deploy/rocketmq/create-topics.sh) 一致，使用目标集群的 nameserver 和集群名，不依赖自动建 Topic：

```bash
"$ROCKETMQ_HOME/bin/mqadmin" updateTopic -n "$NAMESRV" -c "$CLUSTER" \
  -t orders-event -a +message.type=NORMAL
"$ROCKETMQ_HOME/bin/mqadmin" topicRoute -n "$NAMESRV" -t orders-event
```

本地长期使用的 Topic 加到该脚本 `TOPICS`。服务的 `forge.mq.endpoints` 指向客户端可访问的 proxy gRPC 地址，不是 nameserver。`mqadmin` 的失败退出码不可靠，必须确认 `topicRoute` 返回 brokerName。

- [ ] **怎么确认**：生产、消费端启动成功，实际业务事件到达并被幂等消费。实现 TenantCreated 初始化的服务加入 platform 的 `forge.platform.provisioning.services`（值为服务名），重启 platform；创建测试租户后平台初始化明细由 pending 变为 succeeded。主链路 E2E 关闭 MQ，不能用它证明 Topic/开通消息链路可用。

## 4. Nacos 网关路由与前缀声明

- [ ] **做什么**：在同一环境同时增加路由与安全前缀声明。保留原有列表，下面条目应追加，不能覆盖 iam/platform/demo。
- [ ] **在哪里配**：Nacos 的 `forge-gateway.yaml`，本地为 public 命名空间、`DEFAULT_GROUP`（参考 [配置文件](../deploy/nacos/config/forge-gateway.yaml) 与 [发布脚本](../deploy/nacos/publish-config.sh)）。服务须注册到网关可见的 Nacos 命名空间/分组，`lb://` 名称与注册名一致。

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: orders-service
              uri: lb://orders-service
              predicates: [Path=/orders/**]
              filters: [StripPrefix=1]
forge:
  gateway:
    services:
      - prefix: /orders
        realm: tenant
```

- [ ] **怎么确认**：发布配置并重启网关。前缀是单个小写路径段，可含数字、连字符；不允许重复或占用 `/actuator`、`/v3`、`/swagger-ui`、`/webjars`。漏配/非法配置启动失败。`/orders/<业务路径>` 无 Token 返回 401，有有效 Tenant Realm Token 按业务权限放行，Platform Realm Token 被拒；带有效 Token 访问 `/orders/internal/**` 仍被拒。未声明前缀不能访问。服务自身也必须验证 Token、issuer、权限；内部端点只允许服务直连网络访问。

## 5. OpenAPI 聚合

- [ ] **做什么/在哪里配**：服务引入 SDK OpenAPI 能力，提供 `/v3/api-docs`；网关分组由上述 `forge.gateway.services` 自动产生，不另外维护 Swagger URL 列表。服务采用 `server.forward-headers-strategy: framework`，网关 `trusted-proxies` 按实际可信代理配置。
- [ ] **怎么确认**：匿名 GET `/orders/v3/api-docs` 成功；网关 Swagger UI 出现 orders 分组，文档中的外部 servers 地址含网关前缀，Try it out 使用正确 Realm Token 后能调用。只看到分组不等于路由可达。

## 6. App Registry 与套餐开放（有 Sub-App 时）

- [ ] **做什么**：先发布 Sub-App 静态资源，登记 App Registry，再向目标套餐开放应用和权限。
- [ ] **在哪里配**：平台控制台的应用注册页（接口 `/platform/sub-apps`，需要相应平台权限）。填写 `code`、`name`、`version`、`remoteEntries`（环境名 → remoteEntry.js URL）、`sharedRequirements`（共享包 → 最低 x.y.z 版本）。编码必须与 Manifest 菜单的 `app` 一致；环境名与 IAM 的 `forge.iam.sub-app.environment` 一致。入口须供浏览器访问，并配置正确 CORS。
- [ ] 在控制台套餐页（`/platform/plans`）的 `subApps` 加入应用编码、`permissions` 加入所需权限；为目标租户配置该套餐。套餐只是授权上限，还需租户角色拥有菜单和操作权限。
- [ ] **怎么确认**：平台登记可读回；等待注册表事件同步到 IAM 后，目标环境租户登录能看到菜单并加载 remoteEntry，业务请求成功；无该套餐能力或无对应权限的租户/成员不能使用该功能。浏览器无共享依赖版本、CORS 或资源 404 错误。
