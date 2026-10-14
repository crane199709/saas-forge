# Platform Console 身份与租户初始化闭环

## 状态与范围

2026-09-11 设计访谈 Q1～Q16 已确认。2026-09-13 已核对 #171～#178 的实现与验收，本文规定的前三项范围完成；逐项证据见 [Issue #170 验收记录](acceptance/issue-170-console-prd.md)。不代表 Issue #165 或整个第 2 阶段完成。

完整覆盖 [MVP 开发计划](16-mvp-development-plan.md)第 2 阶段「Console 交互」前三项：必要权威读取、Platform 登录与会话管理，以及 Quota Definition/Plan → Tenant → Subscription → Tenant Administrator 初始化的产品路径。按此链路分批交付，不以只解除 #165 阻塞代替完整范围。

不扩展后续 OAuth Client 完整管理、Tenant Suspension、Tenant Console 管理员登录与切换页面、设备/历史会话管理、远程踢人、批量操作、导出、自定义排序、未提交表单草稿持久化、套餐变更或续订。OAuth Client 的必要列表与已有详情读取仍在前三项范围内。

## 设计时的能力与差距（2026-09-11）

以下保留设计时基线，不表示当前仍存在这些缺口；最终状态见上述验收记录。设计时源码核实：

- 共享 Authentication Runtime 与 Authentication Shell 已实现 Platform 登录、首次改密、恢复、刷新和登出，已有相关测试及部分真实浏览器证据。本轮补齐缺口和专项验收，不重建认证机制。
- Platform 当前仅有首页与 OAuth Client 占位路由；共享 ConsoleApiClient 暴露 OAuth Client 读取/创建，尚无本切片 Tenant/Entitlement 业务封装。
- 正式契约已有权益引导、Tenant 创建与初始化命令，缺少相应列表/详情和初始化进度读取。
- `getCurrentTenantContext` 返回 Tenant 上下文及 Accessible Memberships，只接受 Tenant User Token；不能替代 Platform Current Session。已有 `getOAuthClient` 详情可复用。
- 当前初始化实现会返回 `503` 与 `Retry-After`，但该 operation 的 OpenAPI 响应声明未完整覆盖；实施时同步修正，不能靠前端猜测错误语义。

证据入口：`saas-forge-contracts/saas-forge-openapi-contracts/v1.yaml`、`consoles/platform-console/src/routes.tsx`、`consoles/shared/app-runtime/src/authentication-runtime.ts`、`docs/acceptance/issue-158-browser-session-security.md`。以上核实不表示本次重新运行了相关测试。

## 页面与必要读取

Quota Definition、Plan、Tenant 提供独立列表和详情。Tenant 详情依次引导建立 Subscription 与初始化管理员；已有 Plan 可复用，不要求每个 Tenant 重建权益配置。Subscription 在所属 Tenant 详情查看，不增加全平台订阅管理页。

| 资源 | 首版读取与筛选 |
| --- | --- |
| Current Session | 当前 Identity 的标识、邮箱、可选显示名及平台角色/授权状态 |
| Accessible Memberships | 复用现有权威契约，不另造候选集合或平行会话机制 |
| Quota Definition | 列表、详情，按编码和状态筛选 |
| Plan | 列表、详情，按编码和状态筛选，展示额度配置 |
| Tenant | 列表、详情，按名称和生命周期状态筛选 |
| Subscription | Tenant 下当前订阅的权威详情，包括有效期和权益信息 |
| OAuth Client | 列表、已有详情，按名称、类型和状态筛选，不返回 Secret 明文 |

集合遵循现有游标分页与统一查询规范。精确字段、路径、排序稳定键和错误响应在 OpenAPI 实施切片中定稿，不通过手写接口绕过契约生成。

Current Session 只证明当前请求的权威身份与平台授权，不保证 Refresh Token Family 仍可用或下一次刷新成功，不展示未经查询的会话剩余时长。刷新继续由既有 Runtime 管理；不解析 JWT 拼装权威身份，不用该读取替代各业务 operation 的授权。

## 领域所有权与展示

沿用 [Glossary Map](../GLOSSARY-MAP.md) 和既有词汇：IAM 拥有 Identity、Platform Role 和认证事实；Tenant Access 拥有 Tenant、初始管理员关系及初始化工作流；Entitlement 拥有 Subscription、Plan 和 Quota Usage。必要跨服务读取通过正式契约协作，不跨库拼接或复制领域所有权。

Tenant 详情分别展示 Tenant 生命周期状态、初始管理员、当前 Subscription、`max_users` 上限与权威已用量。

- 初始管理员是首次激活时确定的历史 Membership 关系，不等于当前管理员集合。
- Quota Definition 是额度类型，Quota Usage 是当前已用量；初始化成功不意味着前端可以推算用量为 1。
- Subscription 的持久 `ACTIVE` 状态不能单独证明尚未到期；有效性以权威服务按有效期判断为准。
- Tenant Administrator Initialization 成功与密码设置通知投递分开表示。Tenant 已激活后，邮件失败不得将页面改成初始化失败或重复执行初始化。
- 通知展示待投递、已交付邮件服务或需处理等服务端能够证明的结果；不得把邮件服务接收等同收件箱送达。适用时复用已有重新发送密码设置通知 operation。

详情局部读取失败时保留已确认内容，在失败区域显示暂时无法读取并允许重试；不渲染为无订阅、无管理员或用量 0。依赖未知数据的动作暂时禁用。会话失效由共享 Runtime 统一处理；不同会话的迟到响应不得回填当前页面。

## 最小权益边界与额度新规则

保持本阶段仅有全局唯一 `max_users` Quota Definition、恰好一个额度项的 Plan，以及每 Tenant 的首个 Subscription。已有 `max_users` 时复用；不新增 `max_projects`、多订阅、换套餐、续订或权益编辑。

本次明确改变正式规则：**新 Plan 的 `max_users` 上限最小为 1**。前端、正式请求契约和后端校验必须一致；不得仅限制表单。Quota Usage 以及释放后已用量仍允许为 0。

历史 0 额度处理：

- 旧 Plan、既有订阅权益和历史幂等响应保持可读、保持事实不变，不自动改为 1、不删除记录。
- 禁止新建或激活 0 额度 Plan，禁止为历史 0 额度 Plan 创建新 Subscription。页面明确标示不满足新规则，不提供这些新动作。
- 历史响应重放属于旧操作结果，不应误判为新建；同原操作的重放保持既有幂等语义，不重新授予权益。
- 当前 OpenAPI 的 `PlanQuotaLimit` 同时用于请求和响应，领域构造器也用于恢复数据库记录。实施时区分新写入约束与历史读取能力，不能统一收紧导致旧数据无法读取或旧响应无法解码。
- 当前额度实现关联读取 Plan 额度，并非独立 Subscription 快照；本轮不顺带改造快照机制，尤其不能通过修改历史 Plan 改变既有权益。
- 实施前只读核实存量，不假设历史没有 0。如需数据库约束变更，只能新增满足历史兼容要求的前向迁移，不修改已执行 V2 或历史响应约束。

这是请求合法值范围的收紧，契约兼容性检查应明确报告并按本次已确认规则处理，不伪装为无行为变化，也不得为通过门禁整体关闭兼容性校验。

## 未决操作与刷新恢复

遵循 [ADR 0045](adr/0045-console-operation-recovery-is-server-authoritative.md)；恢复不是再次提交新业务操作，也不是创建一个跨领域通用工作流平台。

### 普通创建与激活

Tenant、Quota Definition、Plan、Subscription 的创建及已有激活命令，当前按原操作者、原 Idempotency-Key 和相同请求指纹重放稳定结果；普通幂等记录保留 24 小时。当前 Client 句柄只在内存中，刷新后失效，不能据此承诺已具备恢复能力。

补充按原操作者隔离的服务端恢复读取，让刷新、浏览器重启或重新登录后能够找到本人未决操作并判断可继续动作。完整请求、管理员邮箱、Token 和凭据不持久保存到浏览器；普通页面不生成或导入任意 Key，恢复封装仍由共享类型化 Client 承担。

网络超时、取消请求或失去响应都不证明服务端未提交；显示结果待确认，先权威查询。保留期内只有能够确认原操作且恢复规则允许时才复用原 Key；超过期限且无法确认结果时明确提示核查，不自动生成新 Key 再创建。Tenant 名称不唯一，不能按名称猜测唯一结果。

读取必须区分已确认未创建、仍在处理、已有结果和无法确定；记录找不到本身不等于安全重试依据。恢复接口的查找方式、必要保留信息与过期表示属于实施前必须完成的契约设计，不假设现有仅存稳定响应的记录已经足够。

### 管理员初始化

初始化沿用 Tenant Access 的持久根工作流，不套用普通操作的 24 小时期限。页面显示结果待确认、处理中、已完成或需要处理等业务含义，精确公共状态和允许动作由正式契约给出，不直接暴露内部执行阶段、租约或数据库字段。

- 自动恢复中避免重复提交，按服务端允许的方式读取进度。
- 自动恢复耗尽后，原发起人可依既有规则用原 Key 继续；补偿完成并明确返回 `TENANT_ADMIN_INITIALIZATION_RETRY_REQUIRED` 时才是需要新 Key 的新尝试。
- 其他具备平台权限的管理员可以查看 Tenant 及初始化权威进度，但不读取原发起人的私有恢复材料或接手其未完成操作。恢复动作重新校验当前权限与原发起人，不因 Key 已知而授予权限。
- 已成功初始化后的通知重发是独立业务动作，不等同接手或重放未完成初始化；仍遵守既有 operation 授权。

未提交表单不保存草稿，离开已修改表单时提示；已创建资源从权威列表/详情恢复，已提交但结果不确定的操作不能按草稿丢弃。未提供自动无限轮询、无限重试或浏览器业务恢复存储。

## 交付顺序与测试边界

1. 对照已实现认证与已有证据补差距；先设计 Current Session、业务读取和操作恢复正式契约，生成服务端接口与 Client，验证授权与响应语义。
2. 实现 `max_users >= 1` 新操作规则和历史 0 读取兼容；保留合法正额度耗尽的真实拒绝测试，替换依赖新建 0 额度 Plan 的旧场景，不仅删除覆盖。
3. 扩展共享 Runtime 类型化业务能力及恢复流程；复用现有设计系统、布局、错误与中英文资源机制，再接入各页面。
4. 经真实 Console → 受信 HTTPS → Gateway → Nacos 发现的服务验证产品链路，并记录相关 Fresh Compose 证据；不将诊断脚本或生成 Client 单独当作产品验收。

必要验收包含：

- 登录、首次改密、刷新、登出、登录保护、错误凭据、会话失效与可恢复故障；Current Session 不伪称未来 Refresh 有效。
- 创建/激活 `max_users` 与正额度 Plan，创建 Tenant 和 Subscription，初始化管理员，权威读取 Tenant、初始管理员及 Quota 实际变化。
- 游标列表与筛选、详情刷新、浏览器重启后的已持久状态恢复、中英文代表路径及沿用的无障碍基线。
- 重复提交、服务端已提交但响应丢失、未提交与结果未知的区别、24 小时边界、初始化恢复与补偿后的新尝试；越权读取/恢复及跨操作者恢复拒绝。
- 邮件投递失败时 Tenant 仍激活且额度不反向回滚；合法重发不会重复初始化或扣减。
- 新建/激活/订阅 0 额度拒绝，历史 0 数据与旧幂等响应可读且权益不变，Quota Usage 可归零，合法正额度的耗尽仍拒绝。
- 局部读取失败不伪造空结果，认证失败和迟到响应沿用共享 Runtime 安全边界。

按受影响范围执行回归；Flyway、Nacos、国际化和既有浏览器专项门禁仍按仓库规范执行。通过、失败、跳过与未执行分别记录，不预先勾选开发计划。

本切片完成不等于第 2 阶段整体完成。#165 仍需另外记录原生 IDE 生命周期、真实 Tenant/Quota 联调、注册地址或端口变化及无健康目标拒绝；本规格不自动关闭该 Issue。
