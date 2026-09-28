# 完整 Compose 集成验收

> 当前浏览器入口在独立 saas-forge-web。本文只保留后端环境与引导操作；检查迁移状态见[#207 清单](../../docs/acceptance/issue-207-cleanup.md)。

本目录专用于完整集成验收，复用各服务 Compose 并恢复跨服务启动门禁。日常独立启停见[运行环境说明](../compose/README.md)。下列维护命令均针对当前验收项目。请使用专用 `.env`，并将所有 Secret 路径填写为绝对路径；不要把旧 `.env` 的相对路径直接复制过来。


日常应用开发从[原生开发总入口](../../docs/native-local-development.md)开始，使用前台 `pnpm run dev` 与 IDE Run/Debug。下列完整 Compose 与替换工具用于集成验收、演示和复现；依赖可独立准备，完整应用编排不是日常启动前提。

[English](README-en.md)

本目录提供 saas-forge 的最小本地运行拓扑，供开发、演示和端到端测试使用。默认 `compose.yaml` 只启动后端及基础设施，不包含统一 Console 或浏览器 HTTPS 入口。

## 包含内容

- Gateway；IAM、Tenant Access、Entitlement、Audit 四个领域服务
- PostgreSQL 18、Mailpit，以及四个服务各自的一次性 Flyway 迁移任务
- Redis、单节点 KRaft Kafka、单节点 Nacos 与 OpenTelemetry Collector
- PostgreSQL、Redis、Kafka 的独立命名卷

S3 兼容对象存储不属于当前拓扑：按 [ADR 0036](../../docs/adr/0036-tenant-access-owns-controlled-tenant-brand-profiles.md) 随第 4 阶段 Tenant 品牌素材引入最小能力，第 6 阶段的 Audit 导出在分离的存储边界内复用。当前 Collector 仅通过 `debug` exporter 输出遥测数据，不部署 Prometheus、Loki、Tempo 或 Grafana。

## 启动

在本目录执行：

```bash
test -f .env || cp .env.example .env
# 为 .env 中的全部变量填写仅用于本地开发的值
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-acceptance}" bash ../../scripts/initialize-local-iam-signing-key.sh
docker compose config
docker compose up --build
```

初始化脚本会在 `.secrets/` 中生成 Git 忽略的 PKCS#8 RSA 私钥，执行 IAM Flyway 迁移，并在数据库尚无 ACTIVE Signing Key 时写入与该私钥公钥一致的本地元数据。脚本可重复执行；若数据库已有不匹配的 ACTIVE Key，则拒绝覆盖并要求显式轮换。

首次启动时，Nacos 会用 `.env` 中的显式管理员密码完成首次初始化，并由 `nacos-init` 创建非默认的 IAM、Tenant Access、Entitlement、Audit、Gateway 开发身份、配置发布身份、`dev` namespace，以及 `SAAS_FORGE` group 中各自的配置资源；PostgreSQL healthy 后四个 `*-migrate` 任务会完成各自数据库迁移；对应领域服务随后启动，Gateway 最后启动。Compose 明确向所有应用传入 `NACOS_TLS_ENABLED=false`，因为它只提供隔离网络内的单节点开发 Nacos；不得将此拓扑、地址或凭据复制到生产。五个应用均以 `refreshEnabled=false` 导入自身配置资源，常规配置变更由受控发布流程配合滚动发布生效；当前没有可在本地热更新的策略。任一服务的 Nacos 配置不存在、Nacos 不可达或注册失败时不会 Ready；Gateway 仅通过 Nacos 的健康实例代理当前公开路由所属服务，Audit 注册不会开放新入口。Nacos 本地控制台访问 <http://127.0.0.1:8849/>。可用以下命令查看状态：

```bash
docker compose ps --all
```

`*-migrate` 显示 `Exited (0)` 表示迁移成功。当前后端已有认证与管理 API；服务根路径没有页面，直接请求根路径返回 `404` 不代表 API 不可用，也不能据此判断服务已就绪。

## 统一 Console 与浏览器验收

页面在独立 [saas-forge-web](https://github.com/crane199709/saas-forge-web) 构建和启动，使用锁定 Client 与 Node/pnpm。在前端执行 `pnpm run verify`、`pnpm run dev`，按其说明在个人忽略配置中设置受信 Console/API Origin。本后端 Compose 不构建、挂载或管理前端应用。

按[独立交接](../../docs/acceptance/independent-verification.md)准备隔离环境并传递可核验标识；v2 仅按[#204 受控切换](../../docs/acceptance/issue-204-unified-console.md#受控切换)启用。移除旧 UI 不授权停用外部消费者。浏览器连接已启动的 HTTPS Console/Gateway，环境准备和清理由操作者负责。

TLS Edge 保留独立 `start|status|stop edge`；旧 Platform/Tenant/all 托管和浏览器编排命令已移除。Remote 使用外部只读前端制品，后端继续验证静态交付与精确 CORS。Fresh、安全、视觉与无障碍待聚合项见[#207 清单](../../docs/acceptance/issue-207-cleanup.md)，历史命令从 Git 追溯，不作为当前操作指令。

## Tenant 生命周期全新卷验收

仓库根目录提供一次性验收脚本。它为每次运行生成独立 Compose 项目、随机宿主机端口、临时 Secret 与全新 PostgreSQL、Redis、Kafka 数据卷，不读取或修改 `deploy/compose/.env` 和开发栈数据：

```bash
bash scripts/verify-tenant-lifecycle-e2e.sh
```

脚本会构建当前源码，显式引导 Platform Admin 与三个保留服务 Client，完成首次改密、Quota/Plan、PENDING Tenant、Subscription、管理员初始化、Mailpit Password Setup 和 Tenant Context 登录，并验证无平台角色、错误 Scope、IAM 不可用、额度耗尽、Tenant 到期、凭证冲突、跨 Tenant RLS 及敏感明文边界。无论成功或失败，临时 Compose 项目、数据卷和 Secret 都会清理；不得把临时目录中的凭据复制到日志或仓库。

## 显式引导 Platform Admin

Platform Admin 不随 IAM 正常启动自动创建。它必须通过一次性 bootstrap 任务显式创建，随机初始密码只能用于首次登录，并须在创建后的 24 小时内修改为正式密码。

### 1. 配置 Secret 文件路径

`.env` 只配置外部 Secret 文件路径，不保存邮箱或密码明文：

```dotenv
IAM_PLATFORM_ADMIN_EMAIL_FILE=/absolute/path/to/acceptance/.secrets/platform-admin-email
IAM_PLATFORM_ADMIN_PASSWORD_FILE=/absolute/path/to/acceptance/.secrets/platform-admin-password
```

在本目录创建邮箱文件和随机初始密码文件：

```bash
mkdir -p .secrets
printf '%s\n' '你的管理员邮箱' > .secrets/platform-admin-email
openssl rand -base64 32 > .secrets/platform-admin-password
chmod 600 .secrets/platform-admin-email .secrets/platform-admin-password
```

两个文件必须是非空的单行 UTF-8 文本，可以带一个末尾换行。需要使用初始密码登录时，macOS 可将其复制到剪贴板而不在终端显示：

```bash
pbcopy < .secrets/platform-admin-password
```

### 2. 重新构建并启动 IAM

IAM 正常服务与 bootstrap 任务共用 `saas.forge/iam-service:local`，代码更新后只需构建一次镜像：

```bash
docker compose build iam-service
docker compose up -d iam-service gateway
```

如果整套环境尚未启动，也可执行：

```bash
docker compose up --build -d
```

### 3. 执行一次性引导

显式运行 bootstrap profile：

```bash
docker compose --profile bootstrap run --rm iam-platform-admin-bootstrap
```

该任务会等待 `iam-migrate` 成功后再执行，并在一个 IAM 数据库事务中创建 Identity、24 小时 Initial Platform Credential、`PLATFORM_ADMIN` 角色、幂等事实与 Outbox 事件。相同且仍有效的状态可安全重放；邮箱、密码、凭据或角色状态不一致时任务失败且不会覆盖已有数据。Secret 文件内容必须是单行 UTF-8 文本，可以带一个末尾换行；任务日志只输出非敏感标识、到期时间、结果和 Trace ID。正常 `docker compose up` 不启用 `bootstrap` profile，也不挂载或读取这两个 Secret。

如果 Docker 报错 `bind source path does not exist`，说明宿主机 Secret 文件尚未创建或 `.env` 路径不正确。可在本目录检查文件，不输出其内容：

```bash
test -s .secrets/platform-admin-email &&
test -s .secrets/platform-admin-password &&
echo "Platform Admin Secret 文件已准备"
```

完成首次改密后，引导状态会有意发生变化，不应再次运行 bootstrap 任务。

### 4. 在统一 Console 使用初始密码登录

确认前文的浏览器访问条件已满足，打开 [本地统一 Console](https://console.saas.forge.test/)，输入引导时使用的管理员邮箱和初始密码，点击“登录”。初始密码只建立受限会话，页面应进入“设置新密码”，此时不能访问平台管理功能。

初始密码已过期且尚未建立正式密码时，使用下文的“受限重置 Platform Admin 初始凭证”，不要重新执行首次创建任务。若页面显示会话槽位已有活动会话，先按页面提示退出当前 Console 会话。

### 5. 在页面中修改为正式密码

在“设置新密码”页面输入正式密码并点击“更新密码”。密码必须满足以下规则：

- 至少 12 个 Unicode 字符；
- 最多 128 个 Unicode 字符，且 UTF-8 编码后不超过 512 字节；
- 不得包含空格、换行、制表符等 Unicode 空白字符；
- 不得命中系统弱密码库。

成功后页面提示“密码已更新，请使用新密码重新登录。”，初始密码和受限会话均失效。确认成功后，可在 Compose 目录删除已失效的初始密码文件：

```bash
rm .secrets/platform-admin-password
```

若使用了自定义 Secret 路径，应删除对应的旧文件；不要删除仍在使用的服务 Client Secret 或签名私钥。

### 6. 重新登录并检查会话

1. 在登录页输入管理员邮箱与正式密码，点击“登录”，按权威候选选择平台管理上下文。
2. 刷新页面，确认会话恢复后仍能进入首页。网络故障导致恢复结果不确定时，使用页面的“重试恢复”。
3. 点击“退出登录”，应回到登录页；若退出失败，按页面提示重试。再次刷新不应恢复已退出的 Platform 会话。

上述步骤已由前端接入正式 API，无需手动执行登录、改密请求或读取 Access Token。密码、Token 和 Cookie 不得写入 `.env`、Git、日志或聊天记录。`OAuth Client` 菜单已可用于列出、创建、查看详情、轮换或恢复 Secret 以及吊销 Client；Secret 只在首次成功响应中展示一次，操作记录页不重放 Secret。

## 显式引导保留服务 OAuth Client

先在 Compose 目录生成三组仅用于本地部署的固定 Client ID 与 Secret：

```bash
../../saas-forge-services/iam-service/generate-service-client-secrets.sh "$PWD/.secrets"
```

脚本使用 `openssl` 生成 UUIDv7 Client ID 与 256 位随机 Secret，文件权限受 `umask 077` 保护，且不会覆盖已有文件。随后显式执行一次性引导任务：

```bash
docker compose --profile service-client-bootstrap run --rm iam-reserved-service-client-bootstrap
```

首次执行会在同一事务中创建三个固定服务身份。正式轮换后重跑只读校验 Client ID、服务键、固定 Scope，并接受匹配任一当前有效 Secret 的挂载值；过期或吊销的挂载 Secret 要先更新外部文件，已吊销 Client 必须执行 Replacement Job，bootstrap 不会修改或复活它。正常服务启动不会执行引导任务，三个运行时服务分别只挂载自己的 Client ID 和 Secret。Secret 不写入源码、镜像、Compose 值或 Nacos 配置。

### 替换已吊销的保留 Client

先将新生成的 256 位 Secret 写入权限受限的单行文件，再提供规范 UUIDv7 请求 ID、服务键、旧 Client ID 和新 UUIDv7 Client ID：

```bash
export IAM_RESERVED_CLIENT_REPLACEMENT_REQUEST_ID=<uuidv7>
export IAM_RESERVED_CLIENT_REPLACEMENT_SERVICE_KEY=IAM
export IAM_RESERVED_CLIENT_REPLACEMENT_OLD_CLIENT_ID=<revoked-client-uuidv7>
export IAM_RESERVED_CLIENT_REPLACEMENT_NEW_CLIENT_ID=<new-client-uuidv7>
export IAM_RESERVED_CLIENT_REPLACEMENT_SECRET_FILE="$PWD/.secrets/replacement-client-secret"
docker compose --profile service-client-replacement run --rm iam-reserved-service-client-replacement
```

服务键只允许 `IAM`、`TENANT_ACCESS` 或 `ENTITLEMENT`；名称和 Scope 由服务键固定推导，不能作为输入。完全相同重放返回 `ALREADY_REPLACED`，相同请求 ID 绑定不同输入时任务失败并要求人工处理。

## 受限重置 Platform Admin 初始凭证

只有尚未建立正式密码的 Default Platform Admin 可以使用受限重置任务。为每次新重置准备新的 UUIDv7 `resetRequestId` 和新的随机密码文件；相同 `resetRequestId` 仅用于重放同一次操作：

```dotenv
IAM_PLATFORM_ADMIN_RESET_REQUEST_ID_FILE=/absolute/path/to/acceptance/.secrets/platform-admin-reset-request-id
IAM_PLATFORM_ADMIN_RESET_PASSWORD_FILE=/absolute/path/to/acceptance/.secrets/platform-admin-reset-password
```

```bash
docker compose exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d iam_db -Atc "SELECT uuidv7()"' \
  > .secrets/platform-admin-reset-request-id
openssl rand -base64 32 > .secrets/platform-admin-reset-password
chmod 600 \
  .secrets/platform-admin-reset-request-id \
  .secrets/platform-admin-reset-password
docker compose --profile credential-reset run --rm iam-platform-admin-credential-reset
```

任务不启动 HTTP 服务，只挂载上述两个只读 Secret，并在一个 IAM 数据库事务中永久失效全部旧初始凭证、撤销全部 `INITIAL_PASSWORD_CHANGE` 会话族、创建新的 24 小时初始凭证、幂等事实与 Outbox 事件。已有有效正式密码、Default Platform Admin 状态不一致或 requestId 不是规范 UUIDv7 时任务失败且全部回滚。日志不包含密码、Hash 或 Secret 内容。成功后应删除旧密码文件；若要发起另一次重置，必须同时生成新的 requestId 和密码。

> [!IMPORTANT]
> `.env` 仅限本地使用，已被 Git 忽略。必须填写一个 PostgreSQL 管理员用户名及全部必填变量；不要提交 `.env`，也不要将本地短码用于任何非本地环境。

## 本地端口

所有宿主机端口均只绑定到 `127.0.0.1`，不会暴露到局域网。

| 组件                    |    本地端口 | 说明                                          |
| ----------------------- | ----------: | --------------------------------------------- |
| Gateway                 |        8080 | HTTP                                          |
| IAM                     |        8081 | HTTP                                          |
| Tenant Access           |        8082 | HTTP                                          |
| Entitlement             |        8083 | HTTP                                          |
| Audit                   |        8084 | HTTP                                          |
| PostgreSQL              |        5432 | 数据库连接                                    |
| Redis                   |        6379 | 需使用 `REDIS_PASSWORD` 认证                  |
| Kafka                   |       29092 | 主机外部监听；容器内服务使用 `kafka:9092`     |
| Mailpit                 | 1025 / 8025 | 开发 SMTP / 邮件 Web 界面                     |
| Nacos                   | 8848 / 8849 | 配置与服务发现 API / 本地控制台；仅限本地开发 |
| OpenTelemetry Collector | 4317 / 4318 | OTLP gRPC / HTTP                              |

## 环境变量

`.env.example` 包含所需变量名，不提供默认密码。`POSTGRES_ADMIN_USER` 是 PostgreSQL 初始化管理员账号；JWT issuer、Key Version 引用和本地私钥路径提供安全边界内的开发默认值，其余变量均为密码或 Nacos 认证材料：

| 服务                | migrator 密码                     | app 密码                                                                                                                                                       |
| ------------------- | --------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PostgreSQL 集群引导 | `POSTGRES_ADMIN_PASSWORD`         | —                                                                                                                                                              |
| IAM                 | `IAM_MIGRATOR_PASSWORD`           | `IAM_APP_PASSWORD`                                                                                                                                             |
| Tenant Access       | `TENANT_ACCESS_MIGRATOR_PASSWORD` | `TENANT_ACCESS_APP_PASSWORD`                                                                                                                                   |
| Entitlement         | `ENTITLEMENT_MIGRATOR_PASSWORD`   | `ENTITLEMENT_APP_PASSWORD`                                                                                                                                     |
| Audit               | `AUDIT_MIGRATOR_PASSWORD`         | `AUDIT_APP_PASSWORD`                                                                                                                                           |
| Redis               | `REDIS_PASSWORD`                  | —                                                                                                                                                              |
| Nacos               | `NACOS_BOOTSTRAP_PASSWORD`        | `NACOS_PUBLISH_PASSWORD`、`NACOS_IAM_PASSWORD`、`NACOS_TENANT_ACCESS_PASSWORD`、`NACOS_ENTITLEMENT_PASSWORD`、`NACOS_AUDIT_PASSWORD`、`NACOS_GATEWAY_PASSWORD` |

`NACOS_IAM_USERNAME`、`NACOS_TENANT_ACCESS_USERNAME`、`NACOS_ENTITLEMENT_USERNAME`、`NACOS_AUDIT_USERNAME` 与 `NACOS_GATEWAY_USERNAME` 必须是非默认开发身份。`NACOS_AUTH_IDENTITY_KEY`、`NACOS_AUTH_IDENTITY_VALUE` 与 `NACOS_AUTH_TOKEN` 均须填写仅用于本地的随机值；`NACOS_AUTH_TOKEN` 必须是由至少 32 个原始字符生成的 Base64 字符串。`nacos-init` 仅用初始化管理员身份创建 namespace、用户和权限，随后改用仅可写入五个受控配置资源的 `NACOS_PUBLISH_USERNAME` 发布清单；Issue #130 的目标服务身份可额外读取自身健康实例以完成本机替换验证，Gateway 只可读取自身及 `iam-service`、`tenant-access-service`、`entitlement-service` 健康实例。完整清单、CI 发布和应急回写流程见 [`../nacos/README.md`](../nacos/README.md)。

`bootstrap.sh` 在首次创建 PostgreSQL 数据卷时建立 `iam_db`、`tenant_access_db`、`entitlement_db`、`audit_db`，以及各服务独立的 `*_migrator` 和 `*_app` 账号。迁移任务使用 migrator 账号，运行时服务使用 app 账号。

## Nacos 故障恢复验收

在已准备好本地 `.env` 后，从仓库根目录运行：

```bash
bash scripts/verify-nacos-failure-recovery.sh
```

该脚本使用独立 Compose 项目和 `failure-recovery.override.yaml`，不会占用或停止开发栈的宿主机端口和容器。它依次验证 Gateway 无健康 IAM 实例时返回 `503` 且没有静态地址回退、Nacos 短暂停止后已启动 Gateway 继续使用已知健康实例，以及控制面不可用时新的 IAM 实例无法因缺少必需配置而启动。退出时只删除该独立验收项目创建的容器和卷。

## 停止、清理与重新部署

以下命令均在 `deploy/acceptance` 目录执行，适用于本文默认开发栈。先确认操作目标：

```bash
docker compose ls
docker compose ps --all
```

若上次启动指定了 `-p`、`--env-file` 或额外的 `-f` 文件，后续查看、停止、清理和启动必须使用同一组参数，避免清理错项目或遗留旧容器。下文签名密钥初始化脚本支持对应的 `COMPOSE_PROJECT_NAME`、`LOCAL_COMPOSE_ENV_FILE` 和 `LOCAL_COMPOSE_OVERRIDE_FILE` 环境变量；自定义项目也必须同步设置。不要用全局 `docker system prune` 或 `docker volume prune` 代替指定项目的清理。

### 1. 日常暂停，不重新部署

```bash
docker compose stop
# 后续继续运行原容器
docker compose start
```

这组命令保留容器和数据，不重新构建镜像，也不应用源码或 Compose 配置变更。

### 2. 保留业务数据，重新构建部署

适用于升级当前源码、修改部署配置后重建容器。数据库迁移可能改变现有数据结构，有需要保留的数据时应先完成备份并确认可恢复。

```bash
docker compose config --quiet
docker compose down
docker compose up --build -d
docker compose ps --all
```

不加 `--volumes` 时，PostgreSQL、Redis、Kafka 的命名卷会保留，已有平台账号、正式密码和 Tenant 数据继续使用；迁移任务会在服务启动前执行。不要重新创建 Platform Admin，也不要重新生成仍在使用的服务 Client Secret、签名私钥或数据库密码。遇到 Flyway checksum 不一致时，应查明迁移历史差异，不能为了启动而删卷、改历史或关闭校验。

默认 Compose 没有为 Nacos 和 Mailpit 配置持久卷，因此重建容器后，Nacos 由 `nacos-init` 按仓库配置重新初始化，旧 Mailpit 邮件不会保留。需要保留的 Nacos 配置应先按 [Nacos 管理流程](../nacos/README.md) 回写；丢失的密码设置邮件应通过正式 API 重发，不能靠重新引导管理员恢复。

### 3. 清空本地业务数据，从头初始化

仅用于确认可以丢弃的本地开发数据。如果只是发布新版代码，使用上一节。

> [!CAUTION]
> 以下 `down --volumes` 会删除当前 Compose 项目的 PostgreSQL、Redis、Kafka 三个命名卷，包括全部账号、Tenant、订阅、审计记录、会话和消息。需要保留的数据必须先备份并确认可恢复；删除后不能依靠重新启动找回。

```bash
docker compose down --volumes
```

此操作不删除宿主机 `.env`、`.secrets/`、外部 Secret、TLS 证书、前端 `dist` 或已构建镜像。不要把“数据库已清空”理解为“凭据文件也已清空”。重新初始化前：

- 保留并核对 `.env`；不要用 `.env.example` 覆盖已有配置。
- 完整的三组服务 Client ID/Secret 文件可继续用于这个清空后的本地环境，稍后必须重新运行服务 Client bootstrap，将它们写入新数据库。只有六个文件均不存在时才执行 `../../saas-forge-services/iam-service/generate-service-client-secrets.sh "$PWD/.secrets"`；脚本拒绝覆盖，文件部分缺失时先恢复完整材料，不要混用新旧文件。
- 可保留本地 IAM 签名私钥；初始化脚本会为新数据库建立与该私钥匹配的 Signing Key 元数据。只要保留了数据库，就不能通过删除私钥强行重新生成。
- 核对管理员邮箱文件，重新生成本次部署的随机初始密码。以下示例使用默认 Secret 路径；若 `.env` 使用自定义路径，改为对应文件。邮箱文件缺失时，先按“配置 Secret 文件路径”步骤创建。

```bash
mkdir -p .secrets
test -s .secrets/platform-admin-email
openssl rand -base64 32 > .secrets/platform-admin-password
chmod 600 .secrets/platform-admin-email .secrets/platform-admin-password
```

确认上述文件准备完整后，按顺序执行；任一步失败都先处理错误，不要继续引导或登录：

```bash
docker compose config --quiet
docker compose build
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-acceptance}" bash ../../scripts/initialize-local-iam-signing-key.sh
docker compose --profile service-client-bootstrap run --rm iam-reserved-service-client-bootstrap
docker compose --profile bootstrap run --rm iam-platform-admin-bootstrap
docker compose up -d
docker compose ps --all
```

这次数据库已清空，因此需要重新引导管理员并在 24 小时内完成首次改密；原来的正式密码不再有效。已支持的 Tenant、Quota/Plan、Subscription 和 Tenant 管理员通过统一 Console 重新建立；API 准备数据不计作页面验收。

### 4. 更新前端并验证组合

前端独立构建和发布一个 Console 制品，操作以其仓库说明为准；后端更新不强制重发前端。记录两仓提交、固定 Client、运行环境和本轮证据，再按独立交接执行页面验证。保留数据时使用正式凭据；新环境使用本轮引导凭据完成首次改密。检查登录、上下文选择、刷新及整体退出，不能以容器启动代替业务成功。
