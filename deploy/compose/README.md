# 本地运行环境

[English](README-en.md)

本目录只管理 PostgreSQL、Redis、Kafka、Mailpit、OTel Collector、Nacos、Nacos 初始化和共享 HTTPS 入口。各应用分别独立启停；日常应用开发仍使用 IDE Run/Debug 与 `pnpm run dev`。

## 启动基础设施

```bash
cd deploy/compose
test -f .env || cp .env.example .env
# 填写实际配置；不要覆盖已有 .env
docker compose config --quiet
docker compose up -d --wait postgres redis kafka mailpit otel-collector nacos
docker compose run --rm nacos-init
```

PostgreSQL 首次创建数据卷时执行集群引导，创建四个数据库和各自的 migrator/app 账号；它不执行服务业务表迁移。Nacos 初始化会创建身份、权限和配置，重新执行会更新声明的身份密码，应先核对配置。

默认项目名保持 `compose`，继续使用 `compose_postgres-data`、`compose_redis-data`、`compose_kafka-data` 和 `compose_default` 网络。自定义环境项目时设置 `SF_ENVIRONMENT_PROJECT`，并将各目录的 `SF_DOCKER_NETWORK` 设置为同一个网络名；不要给全部应用设置同一个 `COMPOSE_PROJECT_NAME`。

## 应用归属与启停

| 应用 | Compose 所在目录 |
| --- | --- |
| Gateway | `gateway/` |
| IAM | `saas-forge-services/iam-service/` |
| Tenant Access | `saas-forge-services/tenant-access-service/` |
| Entitlement | `saas-forge-services/entitlement-service/` |
| Audit | `saas-forge-services/audit-service/` |

在对应目录复制并填写 `.env.example`，然后执行：

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps --all
# 只停止和移除当前应用的容器，保留共享环境
docker compose down
```

必须先准备依赖环境和服务身份。应用通过共享 Docker 网络访问基础设施，通过 Nacos 发现其他服务；不会自动拉起其他应用。数据库迁移成功后才启动所属容器应用；停止其他服务后，跨服务请求可能失败。独立生命周期不保证缺少业务依赖时仍能完成请求。

Console 镜像只读消费已有 `dist`，启动前先构建对应 Console；它们只提供容器网络内 HTTP，由共享 HTTPS 入口暴露浏览器正式域名。Linux 上将 `CONSOLE_UID`、`CONSOLE_GID` 配置为工件拥有者。前台开发继续使用 `pnpm run dev`，见[原生开发说明](../../docs/native-local-development.md)。

## 凭据与一次性任务

各目录 `.env.example` 只列所需变量，实际 `.env`、`.secrets` 均被 Git 忽略。账号密码必须与基础设施初始化值一致；`*_FILE` 和证书路径推荐填写绝对路径，多个服务可以引用同一个受限文件，不复制实际凭据。相对文件路径按所属服务目录解析，即使由验收配置通过 `extends` 引用也一样。

IAM 首次环境准备可从仓库根目录执行 `bash scripts/initialize-local-iam-signing-key.sh`；它分别使用环境与 IAM 的 Compose，启动 PostgreSQL、执行 IAM 迁移并初始化匹配的签名元数据。脚本不会启动 IAM 常驻应用。IAM 密钥与服务身份的详细维护约束见[验收维护说明](../acceptance/README.md#显式引导-platform-admin)。

| 所属目录 | 任务 | 触发方式 |
| --- | --- | --- |
| 四个业务服务 | `*-migrate` | 随所属容器应用启动；也可 `docker compose run --rm <任务名>` |
| IAM | `iam-platform-admin-bootstrap` | `--profile bootstrap run --rm iam-platform-admin-bootstrap` |
| IAM | `iam-platform-admin-credential-reset` | `--profile credential-reset run --rm iam-platform-admin-credential-reset` |
| IAM | `iam-reserved-service-client-bootstrap` | `--profile service-client-bootstrap run --rm iam-reserved-service-client-bootstrap` |
| IAM | `iam-reserved-service-client-replacement` | `--profile service-client-replacement run --rm iam-reserved-service-client-replacement` |
| Audit | `audit-isolation-replay` | `--profile audit-isolation-replay run --rm audit-isolation-replay` |

表中 profile 命令前加 `docker compose`。维护任务不会随普通应用启动执行。服务身份文件生成工具位于 `saas-forge-services/iam-service/generate-service-client-secrets.sh`，传入目标目录，已有文件不会被覆盖。

## HTTPS 与完整验收

`local-https-development.override.yaml` 保留原生开发 HTTPS Edge；它只连接已有后端，不再通过 `depends_on` 启动 Gateway。旧 Console 的容器 TLS/浏览器验收入口已迁出，见[迁出记录](../../docs/acceptance/consoles-extraction.md)。

完整组合和场景覆盖文件位于 [`deploy/acceptance`](../acceptance/README.md)，通过 `extends` 复用应用定义并保留跨服务启动顺序。共用构建文件位于 [`deploy/docker`](../docker/)。完整验收项目使用自己的网络和数据卷，不接入日常共享网络。

## 从原统一项目迁移

本次文件调整不会自动操作已有容器、数据卷或个人配置。首次切换时，由开发者先停掉旧项目内的应用容器，再启动各独立项目，避免端口和 Nacos 实例冲突。旧应用可通过指定旧项目名、完整验收配置及旧环境变量文件的 `docker compose ... stop <应用名>` 精确停止；不要使用 `--remove-orphans` 清理正在使用的旧应用。

基础设施继续使用原项目名；若原来用了自定义项目名，应填回 `SF_ENVIRONMENT_PROJECT` 并核对网络、卷名称。迁移旧 `.env` 时按各目录模板分配变量，把原相对 Secret 路径转换为指向原文件的绝对路径；不要重新生成已有凭据。原 `deploy/compose/.secrets` 可继续被引用。

环境停止前先停止连接它的应用。`docker compose down` 保留数据卷；`down --volumes` 会删除数据库和消息数据，不属于本次拆分操作。

## 配置验证

从仓库根目录执行 `python3 scripts/validate-compose-layout.py`。该检查使用占位值，不读取实际 `.env`、不启动容器，验证各项目隔离、迁移门禁、路径和所有验收场景。完整联调仍由既有验收脚本与 CI 承担。
