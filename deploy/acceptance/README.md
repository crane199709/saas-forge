# 完整 Compose 集成验收

> 2026-09-22：旧双 Console、前端构建及浏览器验收入口已迁出。本页相关命令仅供历史追溯，不再是当前后端操作入口；服务端迁移、引导及专项服务验收仍保留。当前边界和待迁检查见 [迁出记录](../../docs/acceptance/consoles-extraction.md)。

本目录专用于完整集成验收，复用各服务 Compose 并恢复跨服务启动门禁。日常独立启停见[运行环境说明](../compose/README.md)。下列维护命令均针对当前验收项目。请使用专用 `.env`，并将所有 Secret 路径填写为绝对路径；不要把旧 `.env` 的相对路径直接复制过来。


日常应用开发从[原生开发总入口](../../docs/native-local-development.md)开始，使用前台 `pnpm run dev` 与 IDE Run/Debug。下列完整 Compose 与替换工具用于集成验收、演示和复现；依赖可独立准备，完整应用编排不是日常启动前提。

[English](README-en.md)

本目录提供 saas-forge 的最小本地运行拓扑，供开发、演示和端到端测试使用。默认 `compose.yaml` 只启动后端及基础设施，不包含 Platform Console、Tenant Console 或浏览器 HTTPS 入口。

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

## 当前可在界面完成的操作

以下操作要求前端、HTTPS 入口、后端和对应账号数据均已准备好；仅执行上面的 `docker compose up` 还不能打开产品控制台。

| 操作                                                                          | 当前入口                             | 前置条件或边界                                                                               |
| ----------------------------------------------------------------------------- | ------------------------------------ | -------------------------------------------------------------------------------------------- |
| Platform Admin 登录、首次改密、重新登录、退出                                 | Platform Console                     | 先执行下文的一次性管理员引导；初始密码须在 24 小时内使用                                     |
| 刷新页面恢复会话、多标签页会话同步                                            | Platform / Tenant Console            | 已建立对应会话；平台与租户会话分别管理                                                       |
| Tenant 登录、选择和切换 Tenant、退出                                          | Tenant Console                       | 已通过后端 API 准备可访问的 Tenant 和 Membership；多个可访问 Membership 时才有选择或切换操作 |
| 设置 Tenant 管理员的首次密码                                                  | Mailpit 邮件中的 Password Setup 链接 | 管理员初始化已触发邮件，且链接仍有效；设置成功后返回 Tenant Console 登录                     |
| Platform Admin 创建、初始凭证受限重置                                         | 本文的 Compose 一次性任务            | 没有平台页面入口；受限重置不能用于已建立正式密码的账号                                       |
| 保留服务 OAuth Client 引导、已吊销 Client 替换                                | 本文的 Compose 一次性任务            | 没有平台页面入口                                                                             |
| OAuth Client 管理（列表、创建、详情、Secret 轮换与恢复、吊销、操作记录）      | Platform Console `/oauth-clients`    | 需要 Platform Admin 会话；操作记录页只展示已提交的操作，不重放 Secret                        |
| Tenant 与 Plan、Quota Definition 管理                                         | Platform Console `/tenants`、`/plans`、`/quota-definitions` | 需要 Platform Admin 会话；涵盖列表、创建与详情                                            |
| Subscription 管理、Tenant 管理员初始化                                        | 正式后端 API                         | 尚无平台页面入口；不能将接口能力视为已交付的页面功能                                         |

Platform Console 首页提供总览，Tenant 工作台当前只展示认证状态，不含统计 Dashboard 或业务管理操作。API 调用示例可参考 [Tenant 生命周期验收脚本](../../scripts/verify-tenant-lifecycle-e2e.sh)，不能将脚本中的接口能力视为已交付的页面功能。

### 浏览器访问前提

1. 构建并分别托管 `consoles/platform-console/dist` 与 `consoles/tenant-console-shell/dist`，构建方法见 [Console README](../../consoles/README.md)。当前默认 Compose 不负责这一步。
2. 本地 `platform.saas.forge.test`、`console.saas.forge.test`、`api.saas.forge.test` 均解析到 `127.0.0.1`，经浏览器信任的 TLS 证书在 HTTPS 443 提供访问；前两者分别指向两个前端，API 入口反向代理到 Gateway。不能用不同的 HTTP localhost 端口代替这组入口。
3. 部署时将两个前端的 `/runtime-config.json` 替换为以下内容。构建制品中的原始文件是故意非法的模板，未替换时页面会停在配置错误状态。

   ```json
   {
     "schemaVersion": 1,
     "apiBaseUrl": "https://api.saas.forge.test"
   }
   ```

4. 密码设置链接的 `/password-setup` 页面由 Tenant Console 提供，通过共享 Client 向配置中的 API Origin 提交。旧静态资源 `/password-setup/app.js`、`/password-setup/styles.css` 和同 Origin 提交路径 `/api/v1/auth/password-setups` 保留 Gateway 转发。
5. 后端迁移与服务就绪，并完成下文的管理员及保留服务 Client 引导。Tenant 操作还需要对应的 Tenant、Membership 和有效密码；新环境不会自动生成这些业务数据。

浏览器 Cookie、Origin 和 Fetch Metadata 由浏览器及共享 Client 按协议处理，页面操作不需要手动复制 Token 或 Cookie。HTTP `8080` 是后端本地端口，不是产品控制台入口。完整部署边界见 [部署文档](../../docs/14-deployment.md)。

### 受控 HTTPS Console 开发入口

在 macOS Docker Desktop 上，从仓库根目录执行一次性准备，再显式选择 Console：

```bash
bash scripts/local-development.sh setup
pnpm --dir consoles run build:static-remote
bash scripts/local-development.sh doctor
bash scripts/local-development.sh frontend start platform
bash scripts/local-development.sh frontend start tenant
bash scripts/local-development.sh frontend status tenant
bash scripts/local-development.sh frontend stop tenant
bash scripts/local-development.sh frontend stop platform
bash scripts/local-development.sh frontend start all
bash scripts/local-development.sh frontend status all
bash scripts/local-development.sh status
bash scripts/local-development.sh frontend stop all
```

`setup` 复用有效的本地 CA；服务器证书缺少受控 Host、将在 24 小时内失效或无法通过链/私钥校验时才重签 leaf。证书覆盖 `platform.saas.forge.test`、`console.saas.forge.test`、`api.saas.forge.test`、`remote.saas.forge.test`。旧双/三 Host 安装需重新执行 setup；hosts 和 Keychain 变更仍分别要求交互式明确授权，已配置时幂等跳过，非交互环境拒绝系统变更。

`frontend` 必须提供 `start|status|stop` 和 `platform|tenant|all`。Platform Vite 固定监听 `127.0.0.1:5173`，Tenant 固定监听 `127.0.0.1:5174`，均启用 strict port，分别只接受对应受控 Host，HMR 使用对应 HTTPS Origin 的 WSS 443。Edge 通过 `host.docker.internal` 访问两个回环 Vite，将 API 转发到当前 Gateway，并保留浏览器安全头。未知 Host 被拒绝；不得为解决 Docker Desktop 连通性问题将 Vite 改为所有网络接口。

启动使用 Node `24.14.1`、pnpm `11.22.0` 和既有依赖，复用健康兼容的 Edge。日常启停不生成证书、不修改 hosts/信任、不安装依赖、不生成 API Client，也不启动后端或重置账户。已有不兼容 Edge 占用 443 时会阻止启动；升级旧 Edge 前先停止两个 Console，再重新启动。

`start all` 在启动前捕获两个 Console 与 Edge 状态并预检；两个正式 HTTPS Host 都就绪后才成功。已有健康进程与兼容 Edge 会被复用，重复启动不重启资源；任一步骤失败只回收本次调用新启动的进程与 Edge。`stop all` 先核验两个目标，再停止受管 Vite 和当前项目 Edge；未知 PID、端口归属或 Edge 配置错误会阻塞变更。

`frontend status all` 只读显示两个 Console 的固定端口、HTTPS 就绪结果和共享 Edge；顶层 `status` 先输出这些信息，再执行原有五个后端服务检查。正常 `STOPPED`、可识别的 `STALE` 与短暂 `STARTING` 不使前端聚合失败；`UNREADY`、`UNMANAGED`、Edge `INVALID` 或 `UNAVAILABLE` 返回非零。状态输出不包含凭据或原始环境变量。

两个 Console 各自使用 Git 忽略目录 `deploy/compose/.secrets/local-https-development/` 内的 `platform-vite.pid|log` 和 `tenant-vite.pid|log`，PID 与追加日志权限均为 0600。`status` 报告 `RUNNING`、`STOPPED`、`STARTING`、`STALE`、`UNMANAGED` 或 `UNREADY`；RUNNING 要求 PID、进程组、启动时间、仓库、包身份、回环监听和正式 HTTPS 就绪全部匹配。停止只向身份匹配的目标发送 SIGTERM；另一个 Console 活动或身份不明时保留 Edge，最后一个停止后仅停止 Edge 容器，不删除容器、后端服务或卷。旧 `vite.pid` 只由 Platform 在身份匹配时认领，旧日志保留；陈旧记录仅在确认原进程不存在后清理。

包级 `pnpm --filter @saas-forge/tenant-console-shell run dev` 仍可前台调试；占用 5174 时，统一生命周期报告 UNMANAGED 并拒绝终止它。前台 HTTP 调试不能替代受控 HTTPS 验收。

Tenant Origin 下的 `/password-setup` 进入 Tenant Vite 的正式 Console 路由。旧资源 `/password-setup/app.js`、`/password-setup/styles.css` 和 `/api/v1/auth/password-setups` 精确转发到当前活动 Gateway，查询参数与浏览器请求头原样保留；这些资源的内容类型、缓存头及 API 错误响应由 Gateway 决定。其他 Tenant 路径和 HMR 继续进入 Tenant Vite。

这些路径与 API Host 共用活动目标文件；`bash scripts/local-development.sh replace gateway` 后跟随本地 Gateway，`restore gateway` 后回到容器，无需修改浏览器 URL 或重启 Edge。目标文件缺失、非法或目标不可达时返回 502，不回退到 Vite 或其他 Gateway；未知 Host 返回 421。首次升级路由时，按前述步骤停止两个 Console 后重新启动，以加载新的 Edge 脚本。

账户、Gateway 与后端仍需另行准备。上述路由能力不等同于完整 Tenant 认证验收。

第四域直接交付 `consoles/dist/static-remote-acceptance/` 的只读构建制品，不代理 Vite 或 API；仅精确 Tenant Origin 获得无凭据 CORS，缺失资源返回 404。构建、受控 Edge 升级和真实 Chromium 验收见[第四域开发验收说明](../../docs/local-static-remote-development.md)。此开发切片不代表 Fresh Compose 或父规格 #155 完成。

#### 状态与恢复

旧的无参数 `bash scripts/local-development.sh frontend` 已移除，会返回用法错误。以下九种命令是完整替代入口，均从仓库根目录执行：

```bash
bash scripts/local-development.sh frontend start platform
bash scripts/local-development.sh frontend status platform
bash scripts/local-development.sh frontend stop platform
bash scripts/local-development.sh frontend start tenant
bash scripts/local-development.sh frontend status tenant
bash scripts/local-development.sh frontend stop tenant
bash scripts/local-development.sh frontend start all
bash scripts/local-development.sh frontend status all
bash scripts/local-development.sh frontend stop all
```

| 状态        | 含义                                          | 恢复动作                                                                                                                             |
| ----------- | --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `RUNNING`   | 受管身份、回环监听与正式 HTTPS 就绪均通过     | 正常开发；重复 start 会复用                                                                                                          |
| `STOPPED`   | 没有受管记录，也没有对应端口监听              | 需要开发时显式 start                                                                                                                 |
| `STARTING`  | 受管进程已创建，30 秒启动窗口内尚无监听       | 等待后再次 status；不要并行反复启动                                                                                                  |
| `STALE`     | 有记录，但原进程已不存在，端口也未被占用      | 执行对应目标 stop 安全清理，再 start                                                                                                 |
| `UNMANAGED` | 记录身份不可信，或端口属于未知进程            | 用 `lsof -nP -iTCP:5173 -iTCP:5174 -iTCP:443 -sTCP:LISTEN` 核对；由原启动者结束其前台命令，再 status。不要按端口杀进程或直接删除 PID |
| `UNREADY`   | 受管进程存在，但超时未监听或正式 HTTPS 未就绪 | 检查对应日志并运行 doctor；排除 Edge/证书/依赖问题后，显式 stop 再 start                                                             |

日志分别位于 `deploy/compose/.secrets/local-https-development/platform-vite.log` 与 `tenant-vite.log`；本地查看即可，不复制可能含敏感信息的原始日志到报告。Edge `UNAVAILABLE` 应先检查 Docker Desktop 与 Docker 访问权限；`INVALID`/`UNMANAGED` 需先核对项目归属和配置，不能自动替换未知监听者。`doctor` 的修复提示不授权本次验收修改证书信任或后端。

直接包级前台调试可在 `consoles/` 的两个终端分别执行：

```bash
pnpm --filter @saas-forge/platform-console run dev
pnpm --filter @saas-forge/tenant-console-shell run dev
```

包级命令不生成 API Client；工作区 `dev:platform`/`dev:tenant` 则先生成再启动。两者均不属于受管 PID 生命周期，应由原终端 Ctrl-C 结束；HTTP localhost 显示页面只能说明前端可渲染，不能作为登录、Cookie、CSRF 或 TLS 安全验收证据。

#### 双 Console 产品路径验收与恢复

1. 验收前保存 `frontend status all`、顶层 `status`，以及当前项目 Edge 的容器身份、运行状态和后端容器启动时间。确认已有受信证书、hosts、依赖和后端就绪；本轮不运行 setup、bootstrap、replace/restore 后端或任何密码重置。
2. 依次覆盖 Platform-only、Tenant-only、all、单目标停止、all 停止与重复操作，每步读取聚合状态。单目标停止须保留仍被另一 Console 使用的 Edge；前端 stop 不停止后端、不删除容器、Secret 或数据卷，也不终止未知监听者。
3. 在同一浏览器上下文中，以正常证书校验打开 `https://platform.saas.forge.test` 和 `https://console.saas.forge.test`。检查页面身份、关键内容、错误覆盖层、console/network，并分别记录真实 `/api/*` 方法、脱敏路径与状态码；API Origin 为 `https://api.saas.forge.test`。禁止记录密码、Cookie、Token 或敏感响应体。
4. 使用既有账号或会话分别登录/恢复两个槽位；刷新一侧后另一侧仍可使用，登出一侧后另一侧刷新仍保持登录，再交换方向验证。缺少现有登录前提就记录阻塞，不创建账号或重置凭据。
5. 从 Tenant Origin 打开 Password Setup 文档，检查脚本/样式及表单的真实提交是否到达当前 Gateway。只验证不改变密码的失败路径；缺少可安全提交的前提则记录阻塞，不消费有效 Challenge。错误响应仅证明路由，不代表密码设置成功。
6. 在两个 Console 各临时修改一个可见开发标记，分别观察其受控 WSS Origin 的连接与 HMR 更新，再还原文件。用宿主监听检查证明 5173/5174 仅绑定 `127.0.0.1`，从 Edge 内经 `host.docker.internal` 访问二者，并检查宿主 LAN 地址直连两端口失败。Docker Desktop 无法访问回环 Vite 时停止验收，不回退到 `0.0.0.0`。
7. 无论成功或失败，都还原开发标记并恢复最初的受管前端组合；若验收前仅 Edge 运行，前端 stop 会连带停止它，应核对原容器身份后仅启动该 Edge 容器（`docker start <已核对的原 Edge 容器 ID>`）。未知或异常状态不能通过强杀恢复。最后只读复核前端/Edge 状态和后端身份、启动时间，记录无法恢复的差异。

验收报告逐项区分通过、失败和缺少前提，不把脚本测试或以前的 Platform 证据写成 Tenant 实机证据，也不改写 #126/#131 的历史范围。

`doctor` 会继续检查所有类别，即使其中一项失败。输出使用 `CERTIFICATE_MISSING`、`CERTIFICATE_EXPIRED`、`CERTIFICATE_UNTRUSTED`、`PORT_CONFLICT`、`MIGRATION_FAILED`、`NACOS_UNAVAILABLE`、`SECRET_MISSING`、`INFRASTRUCTURE_UNAVAILABLE`、`DUPLICATE_INSTANCE` 等非敏感分类，并在下一行给出恢复操作；不会显示密码、Token、Cookie、Client Secret、JWT 私钥或原始环境变量值。

### 本机后端服务替换开发

必须显式选择一个目标；其他应用服务与基础设施保持容器化：

```bash
cd ../..
bash scripts/local-development.sh status
bash scripts/local-development.sh replace gateway
# 修改或调试本机服务后
bash scripts/local-development.sh restore gateway
```

| 目标                    | 固定回环 HTTP | 固定回环 gRPC |
| ----------------------- | ------------: | ------------: |
| `gateway`               |        `8080` |             — |
| `iam-service`           |        `8081` |        `9091` |
| `tenant-access-service` |        `8082` |        `9092` |
| `entitlement-service`   |        `8083` |        `9093` |
| `audit-service`         |        `8084` |             — |

`replace` 在停容器前校验目标的固定端口、已成功的迁移（适用时）、Nacos `dev` 配置、受限 Secret、基础设施和依赖服务，以及正式服务名恰有一个健康实例。它不打印凭据、Cookie 或 Token；端口占用、配置不一致、Nacos 重复实例或任何 readiness 失败都会拒绝切换。所有本机 JVM 经回环 Nacos HTTP `8848`、Nacos 3 gRPC `9848`、PostgreSQL `5432`、Redis `6379` 和 Kafka `29092` 复用现有容器化基础设施。

Issue #130 的四个目标只停止选定的应用容器：不会删除卷、停止基础设施或重建其他应用容器。Tenant Access 和 Entitlement 的本机进程通过既有回环 gRPC 端口调用容器化下游；Gateway 的本机进程使用只在 `saas.forge.local-replacement.enabled=true` 时生效的负载均衡映射访问这些端口，避免依赖 Docker 内部 IP。Gateway 替换还将 HTTPS Edge 的 `api.saas.forge.test` 目标在 Git 忽略的受限文件中从 `gateway:8080` 切到 `host.docker.internal:8080`，无需重启 Edge；该文件只接受这两个固定目标和端口 `8080`。恢复时自动还原容器 Gateway 目标。IAM 保留原有的专用调用方重建行为，以将容器调用方指向本机 IAM。

若当前开发栈启动于 Issue #130 的 Nacos ACL 变更之前，必须先通过受控初始化流程补充四个替换目标各自的只读实例发现权限；Gateway 仍只发现自身和正式公开路由目标。不要用管理员身份手工修改 ACL：

```bash
cd deploy/compose
docker compose up --detach --no-deps --force-recreate nacos-init
```

`status` 一次输出五行，每行包含 `CONTAINER`、`LOCAL`、`UNAVAILABLE` 或 `DUPLICATE`，以及固定 HTTP/gRPC 端口、`READY`/`NOT_READY` 和 Nacos 健康实例数。`restore` 终止受管本机 JVM、恢复已有容器，并等待该正式服务名在 `dev` namespace 中重新成为唯一健康实例；重复执行没有额外副作用。整个流程不会运行 Docker build。

命令行直接运行上述命令即可。IDE 中可将同一命令配置为 External Tool 或运行前任务；服务进程仍由仓库脚本管理，避免复制 Secret、环境变量或个人 IDE 配置。修改 Java 后再次执行 `replace <目标>` 会复用已处于本机的进程状态，因此应先 `restore <目标>`，再重新 `replace <目标>` 启动新制品。

准备好受信本地 HTTPS 入口，以及具有正式密码的 Platform Admin 的只读凭据文件后，可执行完整五服务矩阵：

```bash
export SF_LOCAL_REPLACEMENT_PLATFORM_EMAIL_FILE=/absolute/path/to/platform-email
export SF_LOCAL_REPLACEMENT_PLATFORM_PASSWORD_FILE=/absolute/path/to/platform-password
bash scripts/verify-local-development-matrix.sh
```

`SF_LOCAL_REPLACEMENT_PLATFORM_PASSWORD_FILE` 必须指向当前正式密码的受限文件，不能指向首次 bootstrap 的初始密码文件；初始密码在首次改密后即失效。若需要为验收保留当前正式密码，使用另一个 Git 忽略、权限受限的文件，并且不要在终端、日志或聊天中输出其内容。

矩阵按 Gateway、IAM、Tenant Access、Entitlement、Audit 顺序执行“容器初始状态→本机替换→真实操作→容器恢复→同一操作重验”。浏览器实际提交 Platform 登录表单，核对 Platform 总览可见结果、真实 `/api/*` 请求/响应、控制台错误、失败请求与 5xx；另从 HTTP localhost 页面证明该 Origin 仍得不到凭据型 CORS 授权。Gateway 与 Entitlement 使用正式 Quota Definition 操作；IAM 使用正式登录；Tenant Access 使用正式 Tenant 创建；Audit 使用登录产生的 `SESSION_STARTED` Committed Fact，并确认由本机 Audit 消费并持久化。矩阵前后核对五个应用镜像 ID、Compose 数据卷集合、五服务唯一容器实例和 Nacos 实例数。Tenant 创建、首次 `max_users` DRAFT Quota Definition 和审计事实均会保留，不能未经确认删除。它不适用于 Fresh Compose 或生产环境。

### 独立浏览器验收

仓库已有 [Console 认证验收脚本](../../scripts/verify-console-authentication-e2e.sh) 和 [专用 Compose override](console-authentication.override.yaml)，用于全新环境的自动验证。它们不提供长期保留的手动体验环境，也不应直接作为默认开发栈的启动配置。

先准备上述本地域名、受信证书、空闲的 `127.0.0.1:443`，以及 Node `24.14.1`、pnpm `11.22.0`、Docker、OpenSSL、Ruby 和 Console 依赖。默认本地验收还要求 Playwright Chromium、WebKit 与 Chrome 可用并信任证书。从仓库根目录执行：

```bash
export SF_ACCEPTANCE_TLS_CERT=/absolute/path/to/local-cert.pem
export SF_ACCEPTANCE_TLS_KEY=/absolute/path/to/local-key.pem
bash scripts/verify-console-authentication-e2e.sh --preflight
# 预检通过后，构建并运行完整验收
bash scripts/verify-console-authentication-e2e.sh
```

证书必须覆盖 `platform.saas.forge.test`、`console.saas.forge.test`、`api.saas.forge.test` 与 `remote.saas.forge.test` 四个本地域名；示例绝对路径须替换为实际文件。预检仅检查环境，不证明登录、四域资源加载或 CORS 拒绝成功。完整脚本创建独立随机项目和全新数据卷，使用构建后的 Console、真实 API、同一份 `consoles/dist/static-remote-acceptance/` Remote 静态制品，经四个受信 HTTPS Origin 操作浏览器；Chromium 用例额外保留 Remote 请求/响应、无凭据加载、CSS 和图片解码证据。结束时只清理本次项目、数据卷和临时 Secret，不保留供后续手动登录的账号或环境。其结果以本次运行输出为准，不能替代开发模式 `verify:local:static-remote` 的 Vite/HMR 证据，也不宣称跨浏览器矩阵或父规格 #155 整体完成。

`EVIDENCE:` 目录中的 `static-remote-chromium.json`（Chrome/Edge 使用对应渠道名）保留 Remote 子测试的脱敏网络、呈现和控制台证据，成功与失败运行均记录状态，Compose 清理不会删除。该 JSON 的通过不代表整轮验收通过；可用 `SF_BRAND_EVIDENCE_DIRECTORY` 指定保留目录。

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

### 4. 在 Platform Console 使用初始密码登录

确认前文的浏览器访问条件已满足，打开 [本地 Platform Console](https://platform.saas.forge.test/)，输入引导时使用的管理员邮箱和初始密码，点击“登录”。初始密码只建立受限会话，页面应进入“设置新密码”，此时不能访问平台管理功能。

初始密码已过期且尚未建立正式密码时，使用下文的“受限重置 Platform Admin 初始凭证”，不要重新执行首次创建任务。若页面显示会话槽位已有活动会话，先按页面提示退出当前 Platform 会话。

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

1. 在登录页输入管理员邮箱与正式密码，点击“登录”，应进入“Platform 总览”。
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

这次数据库已清空，因此需要重新引导管理员并在 24 小时内完成首次改密；原来的正式密码不再有效。Tenant、Membership、Quota/Plan、Subscription 和 Tenant 管理员需要通过后端 API 重新准备，前端目前不能创建这些数据。

### 4. 更新前端并检查重新部署结果

无论保留数据还是清空数据，默认 Compose 都不会重新部署前端或 TLS 反向代理。若前端源码有更新，在准备好 Console 工具链和依赖后重新构建：

```bash
(cd ../../consoles && corepack pnpm run build)
```

将两个新的 `dist` 分别发布到原 Platform / Tenant 站点，并再次替换各自的 `runtime-config.json`；新构建会重新带入非法部署模板。按照前文“浏览器访问前提”核对 HTTPS、Gateway 代理与 Password Setup 路由。无需仅为重新部署而删除受信 TLS 证书。

完成后检查：

1. `docker compose ps --all` 中迁移任务为 `Exited (0)`，后端服务已就绪；单纯显示容器运行中不代表 API 可用。
2. 关闭旧控制台标签页后重新打开 Platform Console。保留数据时使用已有正式密码；清空数据时使用新初始密码，完成首次改密后再登录。
3. 刷新首页验证会话恢复，再退出并刷新，确认不会恢复已退出的会话。
4. 若需要验证 Tenant 登录与切换，先确认 Tenant 数据已经保留或重新初始化；旧的 Password Setup 链接在清空数据库后不能继续使用。

独立 Console 验收脚本会清理它自己的临时环境，不能替代上述开发栈的重新部署，也不能用其测试账号登录你的开发栈。
