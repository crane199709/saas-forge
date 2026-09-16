# 第 2 阶段浏览器聚合验收

状态：2026-09-14，Q1～Q8 已逐项确认，验收边界已收敛，并发布为 [GitHub Issue #183](https://github.com/crane199709/saas-forge/issues/183)，标记 `ready-for-agent`，关联总计划 #88。本文是验收规格，不代表本轮聚合验收已实现、执行或通过。

关联：[MVP 开发计划](16-mvp-development-plan.md#2-身份与租户最小闭环)、[Platform Console 初始化闭环](30-platform-console-tenant-initialization.md)、[租户访问与 OAuth Client 管理](31-console-tenant-access-and-oauth-client-management.md)。

## 已确认边界

### 同一次全新环境完成主链

同一次全新 Compose 环境中，连续完成 Platform Admin 初始化与登录、最小 Entitlement Bootstrap、Tenant 创建、Tenant Administrator 初始化、邮件 Password Setup、Tenant Administrator 登录、Membership 选择与 Tenant Context Switch。后续安全场景使用本轮创建的真实资源。

允许拆成多个测试，但必须明确数据依赖、执行顺序和同一次运行的证据关联；不得通过直接写库或 API 预建主链业务资源。部署必需的基础设施与服务身份引导仍沿用既有规范。历史验收只能提供补充证据。

### 产品操作与攻击注入

正常操作通过真实 Console。独立测试工具可构造攻击请求、暂扣或丢弃真实响应，以及控制本轮隔离环境的 Redis；每项记录注入点、预期拒绝和恢复条件，同时验证服务拒绝与 Console 的实际反馈。

不得给产品新增 Token 输入或测试入口，不伪造成功响应。注入和清理仅作用于本轮隔离验收环境，不接管开发者维护的服务。

### OAuth 管理与服务消费的组合证据

OAuth Client 创建、Secret 一次展示、结果不确定恢复、轮换与吊销全部通过真实 Platform Console。与 Playwright 同一套验收中的独立服务客户端使用真实凭据换取 Token 并访问受保护接口，证明重叠期间新旧凭据可用、到期后旧凭据拒绝，以及吊销后的签发拒绝与已有 Service Token 拒绝。

浏览器管理操作、服务消费结果和恢复过程必须关联至同一次验收。独立 curl E2E 继续作为后端诊断，不能替代本项组合证据。凭据及 Token 不进入截图、日志、录像或网络追踪等验收产物，沿用既有脱敏要求。

### 双语路径

完整主链使用没有已保存语言偏好的全新浏览器上下文，浏览器语言设置为 `zh-CN`，验证由浏览器语言解析得到中文；不改变产品现有 Locale 选择规则。

随后通过页面切换至 `en-US`，覆盖登录错误、正常登录、Membership 选择、Tenant Context Switch、冻结后的失效提示及解除冻结后的重新登录。Platform Console 与 Tenant Console 分别验证各自 Origin 的语言偏好持久化；语言切换不能丢失表单或触发业务操作。

### 错误判定

预期安全拒绝与故障错误必须关联至具体注入场景、请求和时间段，并验证对应页面反馈及恢复结果。不得笼统忽略全部 `401`、`403` 或 `503`。

未知 Console 错误、未捕获异常、正常路径失败及故障解除后持续报错均阻断验收。日志与浏览器证据继续遵循脱敏要求。

### 时间窗口与到期状态注入

先通过真实 Console 轮换，核对服务权威截止时间与首次轮换提交时间相差 24 小时，并通过独立服务客户端验证新旧凭据均可用。

随后允许仅调整本轮隔离环境中、本轮创建资源的相关时间以制造到期状态，再通过真实接口和 Console 验证旧凭据拒绝与相应反馈。十分钟替代签发期限采用同一原则，覆盖期限内允许及超期拒绝；替代签发不延长原重叠窗口。

这是禁止直接写库规则的有限例外，只用于时间状态注入，不允许预建业务资源、直接设置业务成功结果或跳过真实签发及轮换。注入必须限定隔离项目和资源，校验原值及影响行数；脱敏记录注入前后的时间和关联场景，不调整宿主系统时钟。

精确时间边界继续由对应自动化测试验证。聚合记录明确标记“到期状态注入”，区分真实请求验证与精确边界测试，不声称实际等待了 24 小时或十分钟。

### Runtime Client 的真实服务接收端

复用 [Gateway 服务路由规格](23-gateway-service-scope-routing.md#14-非生产真实接收端)定义的非生产真实接收端，使用真实 Gateway、Starter、Redis 与 Nacos，验证经 Console 创建的 Runtime Client 的允许访问、权限不足和吊销拒绝。

该接收端只存在于验收环境，沿用测试 Registry overlay 与独立测试契约，不进入生产服务目录或默认部署。证据标记为“平台机制验收”，不声称已完成生产 Runtime 或 Project SaaS 业务闭环，也不使用 Reserved Client 管理路径替代 Console 的 Runtime Client 管理。

### Audit 与主链关联

对本轮浏览器业务产生的 `Session Started`、`Tenant Created` 和 `Tenant Context Switched`，由真实服务提交事实，经真实 Kafka 与 Audit 服务消费后，只读查询 Audit 数据库，核对事件、操作者、资源及 Trace 的关联。

记录必须能追溯到本轮具体操作，不得以任意历史记录存在作为通过依据。允许有界等待异步消费，超时保留未完成状态与诊断证据。本轮不新增 Audit Console；审计事实定义与消费规则沿用 [Audit 成功事实规格](24-audit-success-fact-consumption.md)。

## 聚合证据与完成边界

按开发计划四个浏览器验收条目组织同一次运行的脱敏证据：

| 条目 | 必须关联的证据 |
| --- | --- |
| 完整主链 | 全新 Compose 项目及数据卷、连续产品操作、真实邮件 Password Setup、权威 Session/Membership/Tenant Context，以及三类成功事实的 Audit Record |
| 安全拒绝与恢复 | 错误 Token、Refresh 重放、撤销 Token、Redis 不可用、越权 Tenant 切换、Tenant Suspension 后旧 Token 拒绝，以及显式恢复后旧会话仍拒绝、重新登录后的结果 |
| OAuth Client | Console 管理操作、一次展示与结果丢失恢复、真实服务消费、新旧凭据重叠与到期拒绝、替代签发不延长窗口、吊销后的签发与已有 Service Token 拒绝 |
| 双语 | 无已保存偏好的中文完整主链、页面切换英文后的代表性操作、两个 Console 各自的语言偏好持久化 |

运行记录注明代码基线及工作区修改状态、浏览器与 JDK 版本、运行标识、场景结果和脱敏证据位置；区分通过、失败、跳过及未执行。使用桌面 Google Chrome 当前稳定版、JDK 17、受信四域 HTTPS 和正常 TLS 校验，沿用现有兼容与安全边界。

历史局部通过记录不能替代本轮缺失场景。只有四项均有对应证据、相关运行时及浏览器错误判定通过，才可据此更新开发计划；本规格确认不勾选验收项，也不代表远端 CI 或 Issue 关闭已完成。

### 主链切片入口（Issue #184）

`bash scripts/verify-console-authentication-e2e.sh --stage2` 在独立 Fresh Compose 中执行中文产品主链与 Audit 关联。[运行说明与当前验证边界](acceptance/issue-184-stage2-main-chain.md)记录同轮内存复用接口和脱敏产物；该入口不执行后续安全、OAuth 或英文切片，不能单独完成父规格。
