# saas-forge 测试策略

> **2026-09-29 前端迁移状态**：统一 Console 已在独立 saas-forge-web 实现，v2 已实现但启用须受控切换，见 [ADR 0052](adr/0052-unified-console-authentication-uses-versioned-session-protocol.md)。下文双 Console、自建包、`consoles/`、旧命令/截图数量及其完成时态保留原设计或历史事实，不代表当前入口与验收。当前检查见[测试归属](console-testing-baseline.md)，未迁及待聚合项见[#207 清单](acceptance/issue-207-cleanup.md)；原历史勾选不自动证明父 #201 完成。

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。涉及前端界面的部分写作于自建 Design System / React Shell 时期，已由 [ADR 0050](adr/0050-consoles-adopt-soybean-element-plus.md) 替代；现行实现是 Vue 3 + Element Plus + Soybean Admin。

## 目标

测试策略验证领域规则、租户隔离、跨服务契约、完整 SaaS 闭环和生产运行目标。测试不以覆盖率替代安全验证；越权、失效、重放和并发超额必须有明确反向用例。

## 测试层级

日常按影响范围选择检查、边界升级条件、结果记录与完整复现入口见 [本地分层验证](local-verification.md)。局部通过不等于完整验收通过。下表列出规划层级与实际工具，**未实现**项已在该行标出，不得按已配置对待。

| 层级 | 工具与环境 | 覆盖重点 |
|---|---|---|
| 单元测试 | JUnit 5 | 领域状态、Plan / Subscription、错误映射、Token 与缓存策略 |
| 集成测试 | Testcontainers 的 PostgreSQL、Redis、Kafka（对象存储兼容服务**未实现**） | Flyway、RLS、Outbox、Redis 黑名单、JWT、Quota 原子更新、Kafka 幂等消费 |
| 契约测试 | 版本化 OpenAPI 3.1、Protobuf | REST / gRPC 生产者—消费者兼容性、破坏性变更检测、生成 Client 可用性 |
| 前端测试 | `node --test`（边界与集成）、Vitest browser mode + `@vitest/browser-playwright` + `@vitejs/plugin-vue`（组件与消费者）、`vue-tsc` | 共享 admin 组件与布局、认证 Runtime、消费者边界、i18n 资源门禁 |
| 端到端测试 | 真实浏览器（Chromium 日常、Chrome 产品验收）与 Fresh Compose | 登录、Tenant 选择与切换、Tenant 创建/初始化/生命周期、Plan/Quota/Subscription、OAuth Client 管理。邀请激活、Role/Permission、Feature 运行时闭环、Remote 加载与审计导出**未实现** |
| 性能测试 | k6（**未实现**，仓库无 k6 脚本或配置） | 规划：Gateway API、登录/刷新、授权/权益查询、Quota 扣减的延迟与错误率 |
| 安全测试 | 依赖/镜像漏洞扫描、OWASP ZAP 基线扫描（**均未配置**） | 规划：依赖风险、Gateway 常见 Web 风险、错误安全性。`.github/workflows` 中无对应步骤 |

## 第 1 阶段共享前端测试基线

[MVP 开发计划](16-mvp-development-plan.md)第 1 阶段的共享组件测试、无障碍、稳定状态视觉快照与浏览器事项，以共享测试基线为独立交付范围：覆盖共享 `@saas-forge/admin` 组件、认证 Runtime 的交互状态，以及两个 Console 的代表性真实路径。复用已有测试设施，明确覆盖矩阵、补齐矩阵内缺口，并提供真实运行证据。

各阶段的完整业务浏览器闭环仍由对应阶段验收；共享测试基线通过不代表这些业务闭环完成。本项继续遵循 [Console 设计规范](25-design-system.md)的无障碍、主题与视觉要求，以及 [Console 国际化基线](29-console-internationalization.md)的双语和状态保持要求。真实产品浏览器测试复用隔离的全新 Compose 数据卷，不清理开发者已有环境。

### 双语组件与交互覆盖

- 范围内每个公共组件的适用状态、共享交互状态机的每条有效迁移，均在 `zh-CN` 与 `en-US` 下验证。纯逻辑状态机验证行为；语言相关结果通过公开接口或实际 UI 验证，不以私有实现断言替代验收。
- 显式切换语言时，验证输入、弹窗、焦点和进行中的操作保持，继续遵循国际化规格的状态保持要求。
- 真实 Compose 路径以 `en-US` 完整执行本项代表路径，`zh-CN` 覆盖代表性操作与拒绝/恢复；视觉快照仅选择关键稳定状态。

### CI 视觉判定环境

固定 Linux 环境下的 Chromium 作为 CI 视觉差异的权威判定环境，固定浏览器版本、字体和渲染环境，并提供本地使用相同环境的复现入口。首次 Linux 图片基线及后续环境升级产生的图片变化必须经过审阅；不能直接把 macOS 图片作为 Linux 比较基线。浏览器支持范围遵循 [ADR 0046](adr/0046-development-supports-chrome-and-jdk17.md)：Chromium 用于日常功能与视觉测试，本地与 CI 的真实产品验收仅使用桌面版 Chrome 当前稳定版，功能、键盘和无障碍要求继续有效。

### 稳定状态视觉矩阵

以下是**规划矩阵**，当前**尚未建成**。仓库现存视觉基线只有 `consoles/browser-test/__screenshots__/admin.browser.test.ts/` 下的 8 张 PNG：认证登录与恢复的中英文各两张，以及 Soybean 布局在 1024 / 1440 两个宽度下的浅色与深色各一张。仓库内也没有 `playwright.config`，消费者测试通过 Vitest browser mode 驱动 Playwright。

规划矩阵要求每类状态覆盖 `zh-CN` / `en-US` 与浅色 / 深色的完整组合，至少包含桌面与窄屏：

| 状态类别 | 必须覆盖的稳定画面 |
| --- | --- |
| 表单错误 | 字段错误与表单错误汇总 |
| 表格状态 | 加载、空数据与失败 |
| 弹窗 | 打开的普通弹窗、可恢复危险确认与不可恢复危险确认 |
| 认证状态 | 恢复与失败页面 |

布局规划继续覆盖 `1440px`、`1280px`、`768px`、`390px`、`360px` 五个视口，并验证相当于 `320 CSS px` 可用宽度下的阅读与操作。截图固定数据并等待字体、素材和布局稳定，不比较随机动画中间帧。自动无障碍检查与真实键盘验证继续遵循 Console 设计规范的 WCAG 2.2 AA 工程验收基线，不以截图或自动扫描替代交互验证。既有 React/Ant Design 时期的截图只在 `docs/acceptance/` 与 `docs/archive/` 中作为历史证据保留，不作为当前界面的比较基线。

### 全新 Compose 真实产品路径

复用现有认证浏览器套件作为本项真实路径基线，保留已有成功、拒绝、恢复与浏览器安全覆盖，并补齐以下路径的双语缺口：

| 路径 | 必须成立的结果 |
| --- | --- |
| Platform 认证 | 初始密码修改、登录、刷新恢复与登出 |
| Tenant 认证与 Context | 登录、Membership 选择、Context 切换，以及切换已提交后恢复失败的显式重试 |
| 会话隔离与竞争 | 两个 Console 会话隔离、多标签恢复与登出竞争；迟到认证结果不得复活已登出的会话 |
| 错误反馈与边界 | 认证及网络错误可理解，路由错误保留 Shell，根错误提供安全恢复入口；现有仅中文的故障表单用例补齐英文同语义验证 |
| Locale 与品牌 | 切换语言保持交互状态，刷新保持语言偏好，品牌随权威 Tenant Context 生效或完整回退 |

沿用前述英文完整代表路径、中文代表操作与拒绝/恢复的语言分工，并按 ADR 0046 使用 Chrome 执行真实产品验收。真实路径从本次验收专属 Compose 项目的全新数据卷执行，经受控 HTTPS Console、Gateway 与真实服务完成；测试夹具不能单独作为产品路径完成证据。窄屏视口用于验证桌面 Chrome 下的布局与可操作性，不构成移动端浏览器支持承诺。

### 完成依据

实现入口与逐项覆盖清单见[共享前端测试基线](console-testing-baseline.md)，执行结果见对应验收记录。交付时按公共组件、适用状态、共享状态迁移及上述真实路径登记对应测试与执行结果，明确通过、失败、跳过和未执行。只有矩阵内缺口补齐、CI 视觉门禁实际执行且相关全新 Compose 产品证据成立后，才能勾选开发计划中的本项；历史测试源码存在、文档完成或快照被跳过均不能作为通过证据。

## 强制安全用例

- Access Token：验签、过期、错误 `kid`、黑名单 `jti`、Redis 不可用 fail-closed、Tenant 切换后旧 Token 失效。
- Refresh Token：哈希存储、轮换、重放、登出、密码重置、成员禁用与 Tenant 冻结后的撤销。
- 批量会话撤销：回归普通登出的当前 Family 边界；覆盖 Membership/Tenant 当前 Family 与历史 Issuance 匹配、多批次游标恢复、租约接管、重复批次、稳定计数、Redis 先写、部分失败和 Ready=false 重建。
- Revocation Fence：覆盖登录、Refresh 与 Tenant Context Switch 并发，Fence 前/后重试耗尽、显式 Suspension Recovery、解除 ABA、Tenant/Membership 重叠、跨 Tenant 隔离和旧会话不复活。
- Gateway 用户 Token 验证：分别覆盖必需、匿名和可选 Token 路由，以及 `401 / ACCESS_TOKEN_INVALID`、`503 / TOKEN_REVOCATION_STATUS_UNAVAILABLE`、`WWW-Authenticate` 和拒绝请求不转发。
- Client Credentials：Runtime/Reserved Scope 矩阵、Secret 一次展示与签发恢复、固定重叠轮换、整 Client 即时吊销、Redis/Ready fail-closed，以及服务 Token 不可冒充用户或建立 Tenant Context；最高集成接缝必须证明吊销前签发且未过期的真实 Service Token 被至少一个真实服务接收端立即拒绝。
- RLS：以应用数据库角色连接，在 Tenant A 上下文中验证 Tenant B 数据不可读、不可写、不可更新、不可删除；无 Tenant 上下文默认拒绝。
- 授权与权益：平台/租户角色隔离、Permission 与 Feature 的组合拒绝、Subscription 到期、Quota 并发扣减不超额、`operationId` 重试幂等。
- 前端：Remote **尚未实现**，因此"未注册来源不可加载、Remote 不可读取 Token"当前无实现可测；已实现的是两个 Console 的受控 Origin/CORS 拒绝路径与共享 Client 不得注入 Cookie、Origin、Fetch Metadata。菜单隐藏不作为后端授权替代这一要求始终有效。

## 核心端到端闭环

```text
部署平台
→ 平台管理员登录
→ 定义 Feature / Quota
→ 创建 Plan、Tenant、Subscription
→ 初始化 Tenant Admin
→ Tenant Admin 邀请并激活用户
→ 创建组织、Role 与 Permission
→ 用户登录并切换 Tenant
→ 业务服务接入 SDK
→ Permission / Feature / Quota Check
→ 执行业务并写入 Audit
```

浏览器产品验收还必须覆盖 Tenant Console Shell 登录与拒绝/恢复路径；菜单权限、微前端 Remote 加载与拒绝路径**随 Remote 实现后才成立**，当前没有对应实现或测试。

Tenant Suspension 安全闭环的最高集成接缝必须从 Gateway 公网请求进入，经 Tenant Access 根工作流、真实 Client Credentials 保护的 IAM gRPC、双方 PostgreSQL 与 Redis Revocation Index/Fence，再回到 Gateway 验证旧 Token 被拒绝。Happy path 不得使用内存数据库、Mock Tenant Access/IAM 或绕过服务认证；Mock 只用于无法稳定构造的超时、提交失败和重试耗尽注入。

## 质量门禁

- 全仓库行覆盖率不低于 80%，分支覆盖率不低于 70%。
- IAM、Tenant Context、RLS、授权和配额关键模块行覆盖率不低于 90%。
- Maven 单元测试使用 `*Test`，集成与契约测试使用 `*IT`；JaCoCo 聚合两类测试后执行覆盖率门禁，具体模块清单与命令见 [Maven 构建与制品发布](21-maven-build-and-release.md)。
- `saas-forge-quality-gates` 的默认 `verify` 使用 Testcontainers 运行 PostgreSQL 18 数据边界门禁，并验证 Redis 的读写/TTL 和 Kafka 的生产消费基础语义；它们在 GitHub Actions 的 JDK 17 门禁中均为强制检查。
- `master` 仅能通过 Pull Request 合并，且必须通过测试、契约与覆盖率门禁。**漏洞扫描、镜像扫描与 ZAP 基线扫描尚未配置**，在补齐前不得声称已通过这些门禁。单人开发阶段不强制独立批准；团队出现第二位开发者后，要求至少一名独立审查者批准。
- 版本标签触发可追溯的制品发布；**Helm Chart 发布尚未实现**，`.github/workflows/release.yml` 当前只发布 Maven 制品。

## 事件可靠性用例

首个实际事件切片必须使用 PostgreSQL 与 Kafka 集成测试验证：领域提交与 Outbox 快照原子性、Kafka 确认前故障后的同 ID 重投、领取租约接管、同 `orderingKey` 的顺序、按 `(consumerName, eventId)` 的幂等副作用、不同消费者独立处理、CloudEvents/schema 拒绝、隔离与同 ID 重放，以及 `traceId` 从业务入口经 Outbox 到消费者的原样保留。事件工程注册表及其服务/topic/schema 对应关系由 `./mvnw verify` 校验。

Gateway Service Scope 切片必须用非生产真实 Spring Boot 接收端夹具证明 Gateway 与 Starter 共同接受合法 Service Token，并分别拒绝错误令牌类型、缺少 Scope、已撤销 Client、撤销状态不可用和伪造保留头；夹具通过测试专用 Registry overlay 与 OpenAPI 操作进入真实 Route Catalog，不计入生产服务登记。Audit 切片必须使用真实 PostgreSQL 与 Kafka 证明三个成功事实的映射、两个消费者身份互不干扰、提交前不确认、同 ID 去重、10 次指数退避、永久错误立即隔离、隔离投递状态机及保持原事件 ID 的重放。具体矩阵分别见 [Gateway Service Scope 路由设计](23-gateway-service-scope-routing.md)与 [Audit 成功事实消费设计](24-audit-success-fact-consumption.md)。

## 容量、性能与可用性验证

首期规划容量：20 个 Tenant、每 Tenant 500 名活跃用户，共 10,000 名活跃用户；峰值按 10% 同时在线，即 1,000 并发用户。按每位并发用户平均每 10 秒 1 次 API 请求，基线为 100 RPS，并验证 200 RPS 突发余量。审计按 100,000 条/日规划，并保留 2 倍增长余量。

除上传、导出和异步任务外，k6 必须验证外部 API p95 ≤ 300 ms、p99 ≤ 1 s。生产月度可用性目标为 99.9%；Gateway 成功请求率（排除客户端取消和预期 `4xx`）与登录、关键只读操作的黑盒探针共同形成 SLI。30 天周期的错误预算为 43.2 分钟。

开发环境只能通过依赖中断、容器重启、Kafka 延迟和 Redis 不可用等故障注入测试验证达标条件；真实 SLO 由生产遥测审计。
