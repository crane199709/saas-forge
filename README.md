# SaaS Forge

多租户 SaaS 平台核心与 Forge SDK。规格见 [.scratch/saas-forge-mvp/spec.md](.scratch/saas-forge-mvp/spec.md)，术语见 [GLOSSARY.md](GLOSSARY.md)，关键决策见 [docs/adr/](docs/adr/)。

## 仓库结构

```
backend/                 Maven 多模块（JDK 17、Spring Boot 4.0.9、Spring Cloud 2025.1.4、SCA 2025.1.0.0）
  forge-dependencies/    BOM
  forge-sdk/             SDK starter（forge-sdk-*）
  forge-gateway/         网关，聚合 OpenAPI
  forge-iam/             身份与权限服务
  forge-platform/        平台服务
  examples/demo-service/ SDK 端到端验收用例
frontend/                pnpm workspace
  apps/platform-console/ 平台侧 Shell App
  apps/tenant-app/       租户侧 Shell App
  packages/ui/           @crane199709/ui
  packages/sdk/          @crane199709/sdk
deploy/                  本地依赖的 docker compose
```

## 环境要求

- JDK 17（Maven 用仓库自带的 `backend/mvnw`，也可用本机 Maven 3.9+）
- Docker（含 Compose v2）
- Node.js 24+、pnpm 12

## 本地启动

### 1. 启动依赖

```bash
docker compose -f deploy/docker-compose.yml up -d
```

启动 PostgreSQL 17、Redis、Nacos 3、RocketMQ 5（独立的 cluster 模式 proxy，宿主机经 8081 连接）、S3 兼容存储，`nacos-init` 会把 `deploy/nacos/config/` 下的配置发布到 Nacos，`rocketmq-init` 会创建 `deploy/rocketmq/create-topics.sh` 中列出的 Topic。数据卷位于 `deploy/data/`（已忽略），删除该目录即可重置。

| 依赖 | 宿主机端口 | 说明 |
|---|---|---|
| PostgreSQL | 5432 | 库 `forge`，超级用户 `postgres/postgres` |
| Redis | 6379 | |
| Nacos | 8848、9848，控制台 18080 | 控制台账号 `nacos/nacos` |
| RocketMQ proxy | 8081 | gRPC，供 `rocketmq-client-java` 使用 |
| S3 | 9000 | `forge` / `forge-local-secret` |

端口冲突时可用环境变量覆盖，如 `FORGE_PG_PORT=15432`，变量名见 `deploy/docker-compose.yml`。

初始化脚本为每个服务创建独立 schema（`iam`、`platform`、`demo`）和两个账号：

- `<schema>_migrator`：schema 所有者，只供 Flyway 执行迁移；
- `<schema>_app`：只有数据读写权限、不拥有表，供应用连接。

初始化脚本只在数据目录为空时执行；修改脚本后需删除 `deploy/data/postgres` 重建。修改 Nacos 配置后重新执行 `docker compose -f deploy/docker-compose.yml up nacos-init`。

### 2. 启动后端

```bash
cd backend
./mvnw install -DskipTests
```

然后分别启动四个服务（各开一个终端，或在 IDE 中运行各模块的 `*Application`）：

```bash
java -jar backend/forge-iam/target/forge-iam-0.1.0.jar
```

```bash
java -jar backend/forge-platform/target/forge-platform-0.1.0.jar
```

```bash
java -jar backend/examples/demo-service/target/demo-service-0.1.0.jar
```

```bash
java -jar backend/forge-gateway/target/forge-gateway-0.1.0.jar
```

| 服务 | 端口 | 经网关访问 |
|---|---|---|
| forge-gateway | 8080 | — |
| forge-iam | 8101 | `/iam/**` |
| forge-platform | 8102 | `/platform/**` |
| demo-service | 8201 | `/demo/**` |

服务启动时以迁移账号执行本服务的 Flyway 迁移，再以运行账号连接数据库；配置通过 `spring.config.import` 从 Nacos 读取（共享的 `forge-common.yaml`，网关另有 `forge-gateway.yaml`）。

连接池及租户会话数据源不参与配置热刷新；修改数据库连接或连接池参数后，需重启对应服务。

验证：

- Nacos 控制台 <http://localhost:18080> 的服务列表中可见四个服务
- 聚合的接口文档：<http://localhost:8080/swagger-ui.html>（右上角切换服务）
- 示例 i18n 文案：登录后使用 `curl -H "Authorization: Bearer <accessToken>" http://localhost:8080/demo/greeting`；业务接口现在要求 Tenant Realm Token。

### 3. 启动前端

```bash
cd frontend
pnpm install
pnpm dev:platform
```

```bash
pnpm dev:tenant
```

platform-console 运行在 <http://localhost:5173>，tenant-app 运行在 <http://localhost:5174>。

租户登录的演示账号、IAM `demo` profile、签名密钥和接口说明见 [Tenant Realm 最小登录](docs/tenant-login.md)。演示数据需显式启用，默认迁移不插入已知密码的身份。运营登录（Platform Realm）的演示账号与差异见 [Platform Realm 与运营登录](docs/platform-login.md)。业务服务声明 Data Resource、角色上的 Data Scope 与自定义条件、默认拒绝规则见 [数据权限](docs/data-permission.md)。

## 构建与测试

每个 PR、main 分支推送与手动触发都会独立运行三个 GitHub Actions 工作流，可并行执行，各自超时为 30 分钟：

| 工作流 | 检查内容 | 失败报告（保留 7 天） |
| --- | --- | --- |
| Backend quality | Maven `verify`：单测、Testcontainers 集成测试、ArchUnit 架构规则、Spotless 格式检查 | Surefire / Failsafe 报告 |
| Frontend quality | 类型检查、单测、Prettier 格式检查、构建、mock E2E | 单测与格式检查日志、Playwright HTML 报告、测试结果与失败 trace |
| Main flow E2E | 独立容器环境中的真实主链路 E2E | Playwright 报告、测试结果与服务日志 |

本地使用 JDK 17、Node 24、pnpm 12.4.2 和可用的 Docker，按以下命令复现（从仓库根目录执行）：

```bash
(cd backend && ./mvnw --batch-mode install -Parchetype-it)

(
  cd frontend
  pnpm install --frozen-lockfile &&
  pnpm typecheck &&
  pnpm test &&
  pnpm format:check &&
  pnpm build &&
  pnpm build:spike &&
  pnpm exec playwright install --with-deps chromium &&
  pnpm test:e2e --reporter=line,html
)

deploy/e2e/run.sh
```

`build:spike` 为 mock E2E 准备诊断页面与子应用 v2。真实主链路脚本会创建并清理独立容器与数据卷；所需端口见 [E2E 脚本](deploy/e2e/run.sh)。格式检查等早期步骤失败时可能尚无测试报告，原因见对应工作流日志。

## 代码格式化

后端使用 Spotless：Java 用 palantir-java-format 并移除未使用的 import，POM 统一排序与缩进，`src/{main,test}/resources` 下的 XML 用 Eclipse WTP 统一缩进（规则见 `backend/spotless-xml.prefs`，保留 SQL 的手写换行，脚手架模板除外）。格式不合规时 `verify` 失败。

```bash
cd backend && ./mvnw spotless:apply
```

前端使用 Prettier（版本锁定，配置在 `frontend/.prettierrc.json`），不依赖 Java 工具链。

```bash
cd frontend && pnpm format
```

```bash
cd frontend && pnpm format:check
```

## 版本说明

SDK 当前统一版本为 `0.1.0`。版本同步、标签发布与临时仓库自检见 [发布指南](scripts/release/README.md)，升级说明见 [变更日志](CHANGELOG.md)。

- 版本组合锁定见 [ADR-0013](docs/adr/0013-pin-spring-boot-4-0.md)，全部由 `forge-dependencies` 管理。
- 前端 TypeScript 锁定 5.9：TypeScript 7 是原生重写版本，vue-tsc 依赖的编译器 JS API 尚未就绪。
- 本地 S3 使用 MinIO 的社区分支 silo（MinIO 社区版已停止发布二进制，见 ADR-0011），代码只依赖 S3 协议。

真实主链路浏览器验收（双租户、权限、数据范围、即时失效、Realm 隔离）：见 [主链路 E2E](docs/main-flow-e2e.md)，一键执行 `deploy/e2e/run.sh`。

后端普通 `verify` 可在冷缓存执行；`install -Parchetype-it` 还会验证新服务模板，先安装本次 SDK，再让生成工程独立执行 `verify`。业务服务接入见 [接入指南](docs/business-service-guide.md)。
