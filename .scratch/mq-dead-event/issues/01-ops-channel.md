# 01: 运维通道基座

**What to build:** 按 spec 第 2 节，建立 SDK 运维接口的令牌校验与平台侧聚合调用，供本 feature 与 `task-run-history` 复用。

**Blocked by:** —

**Status:** resolved

- [x] SDK 模块校验每服务独立令牌，漏配启动失败，令牌不匹配返回 401
- [x] SDK 依赖不产生循环，`forge-architecture` 规则通过
- [x] 平台可获取服务列表，并经 `lb://<service>` 调用；单服务不可达只标记该服务
- [x] 新增 `platform:ops:read`、`platform:ops:manage`，无权限的 Operator 与 Tenant Token 都被拒绝
- [x] 经网关访问 `/internal/ops/*` 被拒绝
- [x] 测试覆盖错误令牌、漏配令牌、服务不可达和权限不足

## Comments

### 2026-11-07 实现与验证

- 新增 `forge-sdk-ops`：`/internal/ops` 探针及 `/internal/ops/**` 专用令牌安全链；保留服务原有 JWT 链。令牌为空、缺失或含未展开的环境变量占位符时启动失败，401 使用统一信封。SDK 依赖单向指向 security/core。
- IAM 新增服务令牌保护的 `GET /internal/services`，仅返回已上报清单的真实服务。`forge-iam-platform` 是 IAM 为 Platform Realm 权限单独登记的逻辑名，目录过滤它，实际调用 `forge-iam`。
- 平台新增 `GET /ops/services`（网关外部 `/platform/ops/services`）、`OpsClient` 泛型 GET/POST 和 `OpsServiceDirectory`，经专用 LoadBalancer 客户端调用 `lb://<service>/internal/ops`。无实例、连接失败、未引入能力或缺少令牌仅令该服务 `available=false`；分页和结构化 4xx 业务拒绝信封保留。带审计的后续命令调用方须把失败信封转换为其业务异常。
- 新增并申报 `platform:ops:read`、`platform:ops:manage`；无 read 权限的 Operator（即使有 manage）读取服务列表返回 403，Tenant Token 返回 401。网关继续拒绝内部运维路径。
- IAM/platform/demo 与脚手架已接入 SDK。主配置只引用必填环境变量，测试令牌只存在测试资源；本地启动文档说明独立随机令牌，隔离 E2E runner 每次生成随机值。
- TDD：SDK 运维探针、IAM 服务目录、平台服务聚合、下游命令冲突和环境变量漏配均先观察红灯再实现绿灯。
- 已执行依赖 `-am install -DskipTests` 与 Spotless。目标验证全部通过：SDK `OpsHttpTest,ArchitectureTest` 5 项；IAM `ResourceManifestTest,ArchitectureTest` 27 项；platform `OpsChannelTest,PlatformPermissionTest,ArchitectureTest` 11 项；demo `TenantIsolationTest,ArchitectureTest` 7 项；网关内部路径拒绝目标用例 1 项。`bash -n`、`git diff --check` 通过。
- 未重复执行模块全量或完整浏览器 E2E；集成分支交付前统一执行，本票未新增用户界面。
