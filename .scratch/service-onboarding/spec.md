# 服务接入成本：可管理的服务凭证与统一 API 入口

Status: ready-for-agent（本文只记录设计；实施前需用户明确授权）

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准（Service Credential、Service Prefix、API Entry、Resource Manifest）。相关决策见 [ADR-0023](../../docs/adr/0023-service-credential-as-managed-state.md)、[ADR-0024](../../docs/adr/0024-api-entry-stripped-by-shell.md)。

## Problem Statement

2026-11-12 核对：仓库内新增一个 Business Service，需要同时改动 **9 类位置**，并重启 IAM 与网关。

| # | 位置 | 现状 |
| --- | --- | --- |
| 1 | 数据库 schema 与双账号 | `deploy/postgres/init/lib/provision-service.sql`，属必要准备工作 |
| 2 | IAM 上报令牌 | `forge.iam.manifest.tokens.<服务名>` 静态 Map，启动时复制进 `ServiceTokenFilter`，改动必须重启 IAM |
| 3 | 网关路由 | Nacos `forge-gateway.yaml` 的 `routes` |
| 4 | 网关认证前缀 | 同一文件的 `forge.gateway.services` |
| 5 | 租户 Shell 开发代理 | `frontend/apps/tenant-app/vite.config.ts` 逐前缀一行 |
| 6 | 子应用独立宿主代理 | 各子应用 `vite.config.ts` 的 `apiPrefixes` |
| 7 | Shell 镜像部署变量 | `FORGE_API_PREFIXES` 生成 nginx location |
| 8 | 子应用源码 | 业务请求写死 `/<服务前缀>/...` |
| 9 | App Registry 与套餐 | 平台控制台登记、开放 |

前两类是本次要消除的固定摩擦，第 2 类还有安全语义问题：

- 令牌在 `ServiceTokenFilter` 构造时复制成私有 `Map`（[ServiceTokenFilter.java](../../backend/forge-iam/src/main/java/io/saasforge/iam/permission/filter/ServiceTokenFilter.java#L27-L32)），过滤器不是 Bean、无刷新入口，**轮换或停用必须重启 IAM，且重启前旧令牌一直有效**。
- 凭证没有状态：无法单独停用某个服务的凭证、无法审计谁在何时登记、无法在泄漏后只吊销一条。IAM 里也不存在任何凭证表，服务目录只由已上报的 Resource Manifest 派生。
- 浏览器侧没有稳定入口：每个业务前缀都要在 Shell 与子应用宿主各配一次代理，新增服务必须改前端代码。

## Solution

分两批解决，互不阻塞：

**A. Service Credential 成为 IAM 里可管理的状态化凭证。** 管理员在平台控制台为一个服务登记凭证，系统生成随机凭证、库内只存摘要、凭证绑定服务身份；认证时每请求查库，登记与停用对多实例请求级生效；生命周期写审计。静态 `forge.iam.manifest.tokens` 降级为引导与本地开发通道。

**B. 浏览器业务请求统一走 `/api`。** SDK 的 `api()` / `page()` 在路径前加 `/api`，Shell（开发 Vite、生产 Nginx）固定两条代理 `/api` 与 `/iam`，`/api` 在 Shell 侧剥掉后转发网关。网关与 Nacos 契约不变。

**C. 接入诊断（只读）。** SDK 暴露上报状态，平台聚合为"配置已写 / 运行已加载 / 真实请求已验证"三级，直连缺项。

**本批不做：** 网关把 routes 与 services 合并成单一服务声明并动态生效（需要运行时重建安全链，风险高于前两项，见下）。

## Decisions

| 决策 | 结论 |
| --- | --- |
| 批次与范围 | A + B，C 作为可观测性配套；网关单一声明另立 ADR |
| 验收度量 | 固定手改位置数与重启次数（清单式），不用耗时做硬指标 |
| 凭证条数 | 每服务多条，状态 `active` / `disabled`；轮换用"建新 → 部署 → 停旧" |
| 停用语义 | 立即失效，不设自动宽限期 |
| 服务身份来源 | 管理员手填服务名（格式校验），不要求先有 Resource Manifest |
| 管理入口 | 平台控制台 + Operator 权限码 `platform:service-credential:manage` |
| 审计归属 | IAM 写凭证生命周期审计（Platform Realm，`tenant_id` 为 NULL），platform 不重复写 |
| 摘要 | SHA-256，唯一索引，常量时间比较；认证失败审计去重 + 限流 |
| 查库失败 | fail closed，返回 503（依赖不可用），不回退静态 Map |
| 兼容 | 静态 Map 保留并标 deprecated，DB 命中优先，静态命中记 WARN |
| `/api` 剥离 | Shell 侧（Vite rewrite / Nginx rewrite），网关零改动 |
| `/api` 产生方式 | SDK 内建、强制、不可选；子应用源码路径不变 |
| 旧路径 | 一次性切换，不留双轨 |
| 验收环境 | 本地隔离环境；设计须多实例正确；共享环境迁移另立变更单 |
| 多实例验证 | 单实例吊销集成测试 + 禁止该路径缓存的架构约束测试 |

## User Stories

### Business Service 开发者

1. 我希望管理员在控制台登记凭证后我拿到一次明文并填进 `FORGE_MANIFEST_TOKEN`，而不是等 IAM 改配置重启
2. 我希望凭证轮换不需要停服：先建新凭证、我改配置重启、再停旧的
3. 我希望新增服务时不再改 Shell 或子应用宿主的代理配置
4. 我希望子应用里写 `/orders/orders` 这样的路径就自动走 `/api`，不用记住前端入口约定

### 平台 Operator

5. 我希望看到某服务当前有几条凭证、最后使用时间，并只停用其中一条
6. 我希望登记错误服务名时能立刻看到"该服务尚未上报 Resource Manifest"的提示，但不阻止登记
7. 我希望凭证的登记、停用、轮换都留下审计记录
8. 我希望在接入诊断页看到某服务卡在哪一级，而不是只能翻服务日志

### 平台维护者

9. 我希望凭证泄漏时能只吊销一条并确认多实例立即生效
10. 我希望有人以后给认证路径加缓存时测试会失败

## Acceptance

### 接入成本基线（对照现状 9 类 / 2 次重启）

- 前端代理改动 **0 处**：Shell 与 sub-app-kit 固定两条（`/api`、`/iam`），不再随服务增长
- IAM 重启 **0 次**：登记、停用、轮换均请求级生效
- Nacos 仍需 **1 个文件两段**（网关单一声明不在本批）
- 服务侧仍需注入 `forge.manifest.token`，但与登记同一个动作完成，不再需要协调 IAM 改配置
- **禁止**为了达成 0 处而把所有未知路径一律代理：SPA 回退、静态资源、HMR 与其他未知路径必须保持现有行为

### 安全

- 停用后下一次上报在任何 IAM 实例上都返回 401；认证路径不得引入缓存（架构测试守护）
- 明文只在创建/轮换响应中出现一次，库内只有摘要，日志与审计不含明文
- 登记、停用、轮换、认证失败均产生审计；认证失败按服务与来源去重并限流
- 上报时 `reporter == manifest.service()` 的绑定保持（[PermissionService.java](../../backend/forge-iam/src/main/java/io/saasforge/iam/permission/service/PermissionService.java#L56)）
- 内部端点仍不可经网关访问

### 前端入口

- 登录、OAuth 回调、Refresh、Cookie 路径行为不变：认证端点不经过 `api()`
- 同源约束保留（[auth.ts](../../frontend/packages/sdk/src/auth.ts#L869-L882)）
- 开发与生产两条路径都验证：Shell 内业务请求到达业务服务，响应是 API JSON 而非 Vite/Nginx HTML

### 诊断

- 三级状态不自证：第三级只由一次真实 HTTP 探测产生
- IAM 不可用时不阻塞业务服务启动（保持现有取舍），但状态里能看到失败原因分类

## 文档与脚手架同步（在对应工单内同一变更完成）

- [ ] `docs/service-onboarding-checklist.md` §2（凭证改为控制台登记；静态 Map 标注 deprecated）、§4（Shell 代理改为 `/api`）
- [ ] `docs/business-service-guide.md`（凭证获取与轮换步骤）
- [ ] `docs/sub-app.md`（`/api` 约定，删除 `apiPrefixes`）
- [ ] `deploy/images/README.md`（删除 `FORGE_API_PREFIXES`，改为固定 `/api`）
- [ ] `CHANGELOG.md`（破坏性：`/api` 入口、`FORGE_API_PREFIXES` 移除）
- [ ] `forge-service-archetype` 与 `sub-app-kit` 模板（代理固定、`FORGE_MANIFEST_TOKEN` 说明）
- [ ] `GLOSSARY.md` 已加 Service Credential / Service Prefix / API Entry

## Non-goals

- 网关 `routes` 与 `forge.gateway.services` 合并为单一服务声明并动态生效；原因：`GatewaySecurityConfiguration` 是启动期构建的固定安全链，刷新 route cache 不重建安全链，需要单独设计与 ADR
- Service Credential 与 Ops Token 合并（ADR-0021 已明确分离）
- 凭证自动下发到服务（仍由管理员一次性交付、人写入服务配置）
- 套餐与租户角色授权的自动化（服务登记、对外暴露、租户授权仍由各自管理员决定）
- 共享开发环境迁移（另立变更单，需管理员确认）

## Open Questions

无。未决项都在本文 Non-goals 里显式排除。
