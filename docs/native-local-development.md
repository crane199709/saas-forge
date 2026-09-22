# 原生本地开发总入口

日常开发使用应用目录的 `pnpm run dev` 和 IDE Run/Debug。一次性准备、应用生命周期和验证分开执行；遵循 [ADR 0043](adr/0043-native-local-development-is-separate-from-environment-orchestration.md)、[ADR 0044](adr/0044-local-feedback-and-complete-acceptance-use-separate-scopes.md)。本流程的证据与剩余项见 [Issue #169](acceptance/issue-169-native-development.md)。

## 1. 首次准备

使用 JDK 17、Node 24.14.1、pnpm 11.22.0；产品验收使用桌面 Chrome 当前稳定版，Chromium 用于日常功能与视觉测试。JDK 可由版本管理器提供，先用 `java -version` 核实当前终端，而不是仅依赖系统 JDK 注册列表。

1. 按当前功能准备 PostgreSQL、Redis、Kafka、Nacos 等依赖；可以使用已有开发环境或独立基础设施。使用仓库 Compose 时，初始化身份、迁移和凭据规则见 [Compose 说明](../deploy/compose/README.md)，无需把五个应用容器作为原生启动的前置条件。
2. 数据库管理员独立检查迁移状态并执行需要的前向迁移。IDE 应用不执行 Flyway，更新代码后重启不能补齐数据库表。既有 Compose 数据库可参照 [Entitlement 的 info → migrate 示例](native-entitlement-development.md#更新代码后的数据库迁移)，其余服务使用对应迁移任务。历史 checksum 错误按仓库迁移规则调查，不重置数据或盲目 repair。
3. 由已有受控初始化流程准备 Signing Key、Service Client、Platform Admin、Nacos 工作负载及最小 naming 读取权限。既有环境复用有效身份；配置缺失不意味着允许重置账号或重复执行管理员初始化。IAM、Tenant Access、Entitlement 的跨服务权限分别见下表说明。
4. 按[开发配置说明](development-configuration.md)在 IDE 配置连接参数和应用独立的受限凭据目录。业务配置统一从当前开发 Nacos 读取，不再复制个人配置模板；本地文件替代模式仅按文档显式启用。
5. IDE 导入并同步 Maven，使用 JDK 17 和模块 classpath，无需激活任何 profile。仅保留普通 Build 前置动作，不添加 package、托管脚本或 Compose 启动任务。

| 应用 | Main class / classpath | 依赖、凭据及调试步骤 |
| --- | --- | --- |
| Gateway | `io.saas.forge.gateway.GatewayApplication` / `gateway` | [Gateway/IAM](native-platform-auth-development.md) |
| IAM | `io.saas.forge.iam.IamServiceApplication` / `iam-service` | [Gateway/IAM](native-platform-auth-development.md) |
| Tenant Access | `io.saas.forge.tenantaccess.TenantAccessServiceApplication` / `tenant-access-service` | [Tenant Access](native-tenant-access-development.md) |
| Entitlement | `io.saas.forge.entitlement.EntitlementServiceApplication` / `entitlement-service` | [Entitlement](native-entitlement-development.md) |
| Audit | `io.saas.forge.audit.AuditServiceApplication` / `audit-service` | [Audit](native-audit-development.md) |

自身 HTTP 注册端口必须与监听一致，gRPC 使用实例自身的 `grpc.port` metadata；不要配置调用方的下游实例地址或静态回退。

在后端仓库根准备共享 HTTPS 基础设施：

```bash
bash scripts/local-https-development.sh setup
bash scripts/local-https-development.sh hosts
bash scripts/local-https-development.sh trust-ca
bash scripts/local-https-development.sh doctor
```

hosts 和系统信任变更遵循工具已有授权流程。证书、信任或网络检查失败时先排查，不使用忽略证书错误选项。前端依赖与运行命令以独立仓库为准；正式 Client 按[版本化交付说明](versioned-api-client.md)生成和发布。

## 2. 日常启动和停止

先确认依赖已就绪，开发者自行处理重复实例和端口冲突。根据功能选择应用：Platform 认证需要 Gateway/IAM；Tenant 会话增加 Tenant Access；订阅、初始化和配额增加 Entitlement；观察已提交事实的消费时运行 Audit。多个后端可同时由 IDE Run/Debug，日志、断点、Stop 和修改后重启均由 IDE 控制。

独立启动 HTTPS 入口：

```bash
bash scripts/local-https-development.sh start edge
bash scripts/local-https-development.sh status edge
```

本机 Gateway 的入口目标按 [Gateway/IAM 说明](native-platform-auth-development.md#真实浏览器认证)设置 `deploy/compose/.secrets/local-service-replacement/api-target.json`。该文件仅表示 Edge → Gateway；Gateway 及内部调用继续使用 Nacos。Edge 在 Docker 时，Gateway 监听需对它可达，不能将容器内 `127.0.0.1` 当作宿主机地址。

前端从独立 `saas-forge-web` 仓库按其 README 安装依赖并运行 `pnpm run dev`；使用既有受信 HTTPS Console/Gateway 入口。后端不再提供 Platform/Tenant 应用托管命令。停止前端使用所属终端，停止后端使用 IDE；HTTPS Edge 独立管理。

## 3. 修改与反馈

普通前端修改可先跑单文件测试，再使用包级验证；后端选择受影响模块及 Reactor 依赖。具体命令、消费者扩展规则、Flyway/Nacos/国际化专项要求和完整复现入口统一见[分层验证](local-verification.md)。认证、契约、迁移和具体 Issue 所要求的专项验收继续执行。

当前 CI 的 Verify 调用认证 reusable workflow：同一 job 先完整 JDK 17 Maven/workspace（含 Chromium），再以 `--product` 复用制品执行 Chrome Fresh 产品与消费者验证；Tenant/Audit fresh-volume、Nacos 配置/权限/恢复另有独立 job。必要步骤失败会使相应门禁失败。当前仅支持 JDK 17 与桌面 Chrome，历史 JDK 21/多浏览器记录按 [ADR 0046](adr/0046-development-supports-chrome-and-jdk17.md)保留，不作为新验收要求。

完整 Compose、`local-development.sh frontend`、`replace/restore`、后台 JAR 和五服务替换矩阵是集成验收与故障复现工具。使用前按各工具确认环境和进程归属；不与相同端口的原生应用混用。保留工具的恢复行为不代表原生流程会自动管理应用。

## 4. 补验与计时记录

复用已有效的切片证据，先检查 [#169 映射](acceptance/issue-169-native-development.md)中的缺口，不无条件重跑完整矩阵。需要重新验收时：

- 两个 Console：分别记录原生命令、前台日志、Ctrl+C 对另一应用的影响；在正常受信 Chrome 中修改可见内容，记录各自 WSS 更新、页面未整页刷新，随后还原临时修改。
- 至少两个后端：在 IDE Debug 中以正式请求命中断点、继续执行，再做可还原的修改并 Build/重启，记录方法、时间及重启后的实际结果。命令行启动 JAR 不作为 IDE 证据。
- 业务链路：按已有 #163–#166 记录确认登录/刷新、Tenant Context、订阅与初始化后权威 Quota 用量、同一 event_id 的 Audit 消费和停机积压恢复。需补的操作使用专用资源和稳定幂等键，保留失败轮次及资源 ID。
- 资源记录：首次安装/生成/环境准备与日常启动分开；记录启动命令到可用页面或实际业务成功的墙钟、IDE 启动时间、相关验证的 `/usr/bin/time -l` real/RSS、后台依赖和缓存条件。RSS 不是进程树或 Docker VM 总和。用户观察 IDE 编辑/窗口切换是否流畅；命令行响应不等于 GUI 可用性。

5 分钟是日常必要验证目标，不是首次安装、完整验收或所有机器的保证。记录通过、失败、跳过和未执行；范围不同不计算加速比，缺少观察就保留缺口。
