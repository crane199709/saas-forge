# SaaS Forge 第一期（MVP）规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准，关键决策见 [docs/adr/](../../docs/adr/)。

## 1. 目标

1. 提供 SaaS 平台核心：平台侧（Operator）负责 Tenant 的创建、Provisioning 与 Tenant Lifecycle；租户侧提供 Identity、Membership、登录认证、RBAC 功能权限与 ABAC 数据权限。
2. 提供 Forge SDK（后端 Spring Boot Starter + 前端 npm 包），让内部团队快速搭建接入平台的 Business Service 与 Sub-App。
3. 一个全局 Identity 可通过多个 Membership 加入多个 Tenant 并在其间切换。

SDK 第一期面向组织内部团队，接口按「将来可能开源」的标准设计，但不为开源做额外工作。

## 2. 技术栈与版本

| 领域 | 选型 |
|---|---|
| 运行时 | JDK 17 |
| 框架 | Spring Boot 4.0.9、Spring Cloud 2025.1.4、Spring Cloud Alibaba 2025.1.0.0（ADR-0013） |
| 注册/配置 | Nacos（SCA 自带，client 3.1.x），配置使用 `spring.config.import` |
| 网关 | Spring Cloud Gateway + Sentinel |
| 认证 | Spring Security 7 内置 Authorization Server（`spring-boot-starter-security-oauth2-authorization-server`）（ADR-0004） |
| 持久化 | PostgreSQL 17、MyBatis-Plus 3.5.16+（`mybatis-plus-spring-boot4-starter`，显式引入 jsqlparser 模块）、Flyway（Boot 4 需 `spring-boot-starter-flyway`，并显式引入 `flyway-database-postgresql`）（ADR-0007） |
| 主键 | 应用侧生成 UUIDv7（ADR-0014） |
| 缓存 | Redis |
| 消息 | RocketMQ 5.x，客户端 `rocketmq-client-java`（ADR-0012） |
| 对象存储 | S3 协议，AWS SDK for Java v2（ADR-0011） |
| 接口文档 | springdoc-openapi 3.x，网关聚合，Swagger UI（ADR-0010） |
| 可观测 | Micrometer Tracing + OpenTelemetry 导出、Micrometer 指标 |
| 测试 | JUnit 5、Testcontainers；前端 Vitest、Playwright |
| 前端 | Vue 3、Vite、TypeScript、pnpm workspace、Element Plus、Pinia、vue-i18n、`@module-federation/vite`（ADR-0009） |

注意 Boot 4 的变化：Jackson 3（`tools.jackson`）、starter 模块化改名（如 `spring-boot-starter-webmvc`）。

## 3. 系统拓扑

```
浏览器
 ├─ platform-console (Shell App) ─┐
 └─ tenant-app (Shell App) ───────┤  运行时加载 Sub-App (Module Federation)
                                   ▼
                           forge-gateway  ── Redis（version / 租户状态校验）
                ┌──────────────┼──────────────────┐
          forge-platform    forge-iam       Business Services (demo-service …)
                └──── PostgreSQL / Redis / RocketMQ / S3 / Nacos ────┘
```

- **forge-gateway**：路由；按路由校验 Token issuer（Platform Realm 路由只收平台 Token，反之亦然）；校验 Membership `ver` 与租户状态（ADR-0005）；按租户限流（第一期仅全局默认阈值）；聚合 OpenAPI。
- **forge-iam**：两个 Realm（ADR-0003）；Identity、Membership、Operator；登录、切换租户、Token 签发与刷新；Department、Position、Extended Attribute；Role、Permission Code、Data Policy；接收 Resource Manifest。
- **forge-platform**：Tenant CRUD、Tenant Lifecycle 状态机、Plan（Feature Pack + Quota）、Provisioning 编排与状态展示、App Registry。本身作为 Business Service 经 SDK 接入，接受 Platform Realm Token，自身也受 RBAC + ABAC 保护。
- **Business Service**：开发者基于 SDK 搭建，独立部署。

## 4. 仓库结构

```
backend/                     Maven 多模块，groupId io.saasforge，包名 io.saasforge.*
  forge-dependencies/        BOM
  forge-sdk/
    forge-sdk-core/          租户上下文、通用模型（响应信封、错误码）、i18n
    forge-sdk-web/           统一响应信封、全局异常映射、分页参数、traceId（见 unified-api-envelope 规格）
    forge-sdk-security/      JWT 资源服务器、权限上下文、@RequiresPermission
    forge-sdk-tenant/        MyBatis-Plus 租户拦截、RLS 会话变量
    forge-sdk-abac/          策略表达式树、SQL 编译器、内存求值器、数据权限拦截器
    forge-sdk-mq/            RocketMQ 封装、Outbox、幂等消费、租户上下文透传
    forge-sdk-audit/         @AuditLog、审计事件
    forge-sdk-storage/       S3 抽象
    forge-sdk-openapi/       springdoc 默认配置
    forge-sdk-manifest/      Resource Manifest 收集与上报
  forge-gateway/
  forge-iam/
  forge-platform/
  examples/demo-service/     SDK 端到端验收用例
frontend/                    pnpm workspace
  apps/platform-console/
  apps/tenant-app/
  apps/demo-sub-app/         示例 Sub-App
  packages/ui/               @forge/ui
  packages/sdk/              @forge/sdk
deploy/docker-compose.yml    PostgreSQL、Redis、Nacos、RocketMQ、S3 兼容存储
```

Maven 与 npm 制品发布到私有仓库（具体仓库待定）。starter 拆分粒度可在实现时按依赖关系微调，但对外保持「按能力引入」。

## 5. 领域模型要点

### 5.1 Identity / Membership

- Identity：凭证（用户名、邮箱、手机、密码哈希）、全局资料（昵称、头像）。允许自主注册。
- Membership：`(identity_id, tenant_id)` 唯一；状态 `invited | active | disabled | left`；租户内资料（工号等）、主 Department、兼任 Department、Position、Extended Attribute（JSONB）、Role。
- 加入 Tenant 只能通过邀请（第一期）。
- 租户管理员不能修改 Identity 的全局凭证。

### 5.2 Operator

独立表；内置「超级管理员」角色；登录只支持账号密码，MFA 是否强制可配置（MFA 本身不在第一期）。

### 5.3 Tenant Lifecycle

`pending → trial → active → suspended → expired → archived → deleted`（具体合法迁移在实现时以状态机表定义）。删除进入 Cooling-off Period（默认 30 天，可配置）后，经 MQ 通知各服务将本服务中该租户的数据标记删除（逻辑删除，见 ADR-0016）。每次状态变更通过 RocketMQ 广播，SDK 订阅并据此拒绝写入（停用/过期）等。

### 5.4 Plan

- Feature Pack：可用 Permission Code 与 Sub-App 的上限。租户管理员给 Role 授权只能在上限内选择；降级时超限授权自动失效（保留记录），升级后自动恢复。
- Quota：成员数、存储容量等。
- 不做计费与支付，只做到期提醒。

### 5.5 预置角色

每个 Tenant：Owner（唯一、可转让、不可删除）、Tenant Admin（Plan 内全部权限，管理 Membership 与角色）。Business Service 可在 Provisioning 时预置本服务的角色模板。

### 5.6 组织

Department 树使用 PostgreSQL `ltree`；Position；Extended Attribute 定义按租户存表、值存 JSONB，可作为 ABAC 主体属性。

## 6. 认证与会话

1. 用户在统一域名登录（Tenant Realm），方式：账号/邮箱 + 密码，或手机/邮箱验证码。短信与邮件发送为可插拔 SPI。
2. 全局认证成功后：只有一个 active Membership 则自动进入；多个则选择；没有则进入「无租户」页。
3. 签发的 Access Token（约 15 分钟）claims 含 `sub`(identity_id)、`tenant_id`、`membership_id`、`ver`；Refresh Token 绑定会话并轮换。
4. 切换租户：以当前会话换发新 Token（新的 `tenant_id`、`membership_id`、`ver`）。
5. 即时失效：Membership 停用、角色变更、租户停用时 iam 递增 `version` 或更新租户状态（Redis）；网关比对不一致返回 401；前端自动刷新，刷新失败登出。
6. 角色与权限明细不进 JWT，SDK 按 `membership_id + version` 从 Redis 拉取并本地缓存。
7. 两个 Realm 使用不同 issuer；Business Service 经 SDK 用 JWKS 本地验签。

## 7. 权限

### 7.1 功能权限（RBAC）

Role 绑定 Permission Code；后端 `@RequiresPermission("order:approve")`；前端 `v-perm` 指令；菜单按权限过滤后由后端下发。

### 7.2 数据权限（ABAC）

- Data Policy 挂在 Role 上，针对某个 Data Resource。
- 表达式树（JSON）：节点为 `and | or | not` 或比较 `{attr, op, value}`；`attr` 为 `subject.* | resource.* | env.*`；`value` 可为字面量或主体属性引用（如 `${subject.dept_paths_with_children}`）。第一期操作符至少：`eq ne in nin gt gte lt lte contains is_null` 与 `ltree` 的「在子树下」。
- Data Scope 预设（本人、本部门、本部门及下级、全部）是表达式树的模板。
- 两个执行器：SQL 编译器（经 MyBatis-Plus 数据权限拦截器注入列表/更新/删除语句）与内存求值器（单对象校验）。同一策略两者结果必须一致。
- 同一用户多个 Role 的策略之间取并集（OR）。
- 管理界面提供可视化条件编辑器，可选属性来自 Resource Manifest。
- 第一期不做字段级权限。

### 7.3 Resource Manifest

SDK 在服务启动时（也支持 CI 阶段）上报：Permission Code（扫描 `@RequiresPermission`，名称/分组由清单文件补充）、Data Resource 及属性→表列映射、菜单树（节点指向 Sub-App 路由）。iam 按 `service + version` 存储；下线的 Permission Code 标记失效，不物理删除。

## 8. 租户隔离（SDK）

- MyBatis-Plus `TenantLineInnerInterceptor` 自动注入 `tenant_id`；可声明忽略表（全局字典等）。
- 每次获取连接时设置会话变量（如 `SET app.tenant_id`），各业务表同时启用 `ENABLE` 与 `FORCE ROW LEVEL SECURITY` 作为兜底。
- 每个服务在同一 PostgreSQL 实例中使用独立 schema，禁止跨 schema 访问；将来拆分独立库只需改连接配置。
- 每个服务两个数据库账号：迁移账号（schema 所有者，供 Flyway）与运行账号（仅数据读写、非表所有者，供应用）；均不得为超级用户或拥有 `BYPASSRLS`。
- 「当前租户 → 数据源」为扩展点接口，第一期只有单数据源实现。
- Redis key 统一前缀 `{service}:{tenant_id}:`。
- S3 对象 key 前缀 `{tenant_id}/{service}/`，访问经预签名 URL。
- MQ 消息头透传租户上下文，消费端自动恢复。
- 异步线程、定时任务需显式携带或声明租户上下文。

## 9. Provisioning 流程

1. Operator 创建 Tenant，选择 Plan，填写初始管理员邮箱或手机号。
2. 若 Identity 已存在：创建 `invited` Membership 并授予 Owner；否则创建待激活 Identity，发送邀请链接。
3. platform 在本地事务中写入 Tenant 与 Outbox 事件 `TenantCreated`。
4. 各服务经 SDK 订阅，幂等初始化本服务默认数据（预置角色、字典等），并回报初始化结果。
5. Tenant 直接进入 `trial` 或 `active`，不等待全部服务；平台界面展示每个服务的初始化状态，失败可重试。

## 10. 一致性与消息

- 不使用 Seata（ADR-0008）。
- SDK 提供 Outbox：同一本地事务写入 outbox 表，投递器发送到 RocketMQ，发送成功后标记。
- SDK 提供幂等消费：按消息 ID 去重。
- 事件至少包括：`TenantCreated`、`TenantStatusChanged`、`TenantPlanChanged`、`TenantDeleted`（冷静期结束）、`MembershipChanged`、审计事件。

## 11. 审计

SDK 产出三类 Audit Event，经 RocketMQ 异步写入 PostgreSQL（按月分区）：

- 登录：成功、失败、退出、切换租户。
- 权限变更：Role、授权、Data Policy、Membership 变更。
- 操作：`@AuditLog` 注解，记录 who、tenant、action、resource、结果。

租户管理员只能查看本租户的审计；平台查看 Platform Realm 的审计。

## 12. 前端

- 两个 Shell App：`platform-console`（Platform Realm）、`tenant-app`（Tenant Realm）。
- `@forge/sdk`：登录、Token 刷新、租户切换、请求拦截、`v-perm`、当前上下文读取。`@forge/ui`：共享组件与主题 token。
- 路由由后端下发的菜单动态生成。
- Sub-App 接入契约（ADR-0009）：
  - 通过 `@module-federation/vite` 暴露 ESM `remoteEntry.js`，容器名保持稳定；Shell 从运行时配置读取入口地址，以 `registerRemotes([{ name, entry, type: 'module' }])` 注册后 `loadRemote`。入口不得编译进 Shell；工单 02 用外置 `sub-apps.json` 验证，工单 21 起由 iam `GET /auth/access` 下发 App Registry 中当前环境的入口。容器名由应用编码得出：`forge_app_<编码，连字符换为下划线>`（`@forge/sdk` 的 `subAppContainerName`）。
  - Vue、Vue Router、Pinia、Element Plus、`@forge/ui`、`@forge/sdk` 以 singleton 共享；使用 Shell 的 i18n 时，`vue-i18n` 也须共享。两端声明 `requiredVersion`；当前验证使用相同精确版本，尚未验证混合版本兼容。Shell 提供依赖，Sub-App 设置 `import: false`，不自带回退副本；使用 `loaded-first` 共享策略。
  - Sub-App 暴露 `./sub-app`，导出 `routes: RouteRecordRaw[]`（相对路径、应用编码前缀的唯一路由名）；Shell 将其挂到 `/apps/<appCode>`，并在首次导航前完成注册。Sub-App 导出组件，不自行 `createApp`、`createRouter`、`createPinia` 或创建全局 i18n；通过 Shell 的 RouterView 渲染，可使用 RouterLink/useRouter 在本应用路由内导航，不得增删全局路由或改写 history。`sharedReferences` 仅为工单 02 的运行时引用检查接口，不是业务接入必需字段。
  - Sub-App 静态资源随入口部署，使用相对 `base`；跨源入口及 JS/CSS 资源须允许 Shell 源的 CORS。Shell 服务需提供 history fallback，使嵌套路由可直接刷新。Sub-App 本身无需提供独立挂载页。
  - Shell 只加载一次 Element Plus 全局 CSS，在文档根节点维护主题 token（含 Element Plus 派生色）；Sub-App 使用同一套 token，局部语言包使用 local scope。Dialog、Select、Tooltip 可 teleport 到 body；弹层样式不得依赖 Sub-App 根节点祖先选择器。
  - 新版本发布到独立且不可变的目录或源地址，更新运行时入口后刷新页面生效；不在已加载页面内强制替换容器。运行时配置禁用缓存，入口应重新验证或禁用缓存，带 hash 的资源可长期缓存。工单 02 已验证独立端口的两个构建版本；生产 CDN 缓存策略另行验收。
  - 登录、Token 刷新与租户切换只由 Shell App 负责；Sub-App 经共享的 `@forge/sdk` 获取 Token 与租户上下文。
  - 切换租户时 Shell App 卸载全部 Sub-App、清空共享 Pinia 状态后重载。
  - 在 App Registry 注册：应用编码、各环境 `remoteEntry` 地址、版本、所需最低共享依赖版本。Shell App 按菜单与 Plan 允许的 Sub-App 决定加载。
  - 隔离约定：scoped CSS 或 CSS Modules、使用主题 token、禁止修改全局样式、禁止注册/删除全局路由；由 `@forge/sub-app-kit` 的脚手架与 `forge-sub-app-lint` 落实（工单 21，见 docs/sub-app.md）。它是内部可信团队的协作约定，不是 JS/CSS 安全沙箱。
- 第一期只支持简体中文，但文案一律走 i18n key。

## 13. 可观测性

SDK 自动在日志 MDC 中写入 `tenant_id`、`membership_id`、`traceId`；Micrometer Tracing + OTel 导出；Micrometer 指标。第一期不搭建采集后端。

## 14. 第一期范围

按顺序推进：

1. 工程骨架、docker compose，验证版本组合能启动且注册 Nacos（ADR-0013）；随后做 Module Federation 技术验证：Element Plus 弹层、嵌套路由、主题切换、Sub-App 独立部署后更新入口。
2. forge-iam（第 5、6、7 节）。
3. forge-platform（第 5.3、5.4、9 节与 App Registry）。
4. SDK starters（第 7、8、10、11、13 节）。
5. 前端两个 Shell App、`@forge/sdk`、`@forge/ui`、示例 Sub-App。
6. demo-service 端到端验收 SDK。

### 不在第一期

计费与支付、MFA、第三方登录与 SSO 联邦、租户授权运维、独立库租户、字段级权限、子域名/自定义域名、多语言内容、生产部署形态（K8s 等）、可观测采集后端、按套餐配置的限流阈值。

## 15. 验收标准

- 版本组合下所有服务能启动并注册到 Nacos，网关能聚合 OpenAPI。
- 一个 Identity 拥有两个 Tenant 的 Membership：登录后能选择、切换租户，切换后只能看到当前租户的数据。
- 跨租户隔离：绕过 SDK 拦截器直接执行 SQL 时，RLS 仍阻止跨租户读写（Testcontainers 测试）。
- ABAC：同一组策略与数据，SQL 编译器与内存求值器结果一致（参数化测试覆盖全部操作符与 Data Scope 预设）。
- 即时失效：停用 Membership、修改角色、停用租户后，下一次请求即被拒绝或按新权限生效。
- Outbox：模拟 MQ 不可用与重复投递，事件不丢、不重复处理。
- Provisioning：创建 Tenant 后 demo-service 完成幂等初始化，平台可见其状态；人为失败后可重试成功。
- Plan：降级后超限授权失效，升级后恢复；未在 Plan 中的 Sub-App 不被加载。
- 前端 E2E（Playwright）：登录 → 选租户 → 加载示例 Sub-App → 按权限显示/隐藏按钮 → 切换租户。
- 平台侧：Operator 只能访问 Platform Realm 路由，Tenant Token 访问平台路由被拒，反之亦然。

## 16. 待定

- 私有 Maven / npm 制品仓库的具体选择（Nexus、Verdaccio 或现有仓库）。
- 生产数据库是否能提供 PostgreSQL 17。
- 本地开发使用的 S3 兼容存储实现。
