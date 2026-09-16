# 历史决策噪音清单

> **目的**：找出仓库中**被记录成"当前决策/当前事实"、但与实际实现不一致**的内容，供清除，避免 AI 按过时事实实现或验收。
> **审计基线**：`2dc1edb`（2026-09-15，297 commits，默认分支 `master`）。方法：11 个文件切片逐条对照源码/配置/迁移/脚本/CI，另加机械扫描（相对链接、脚本引用、幽灵标识符、ADR 取代链）。
> **状态**：审计已完成，**第 1、2、3 批处置均已落地**（`cdba104`、`273e4a2`、`bc69d16` + `0ee459e` + 本文所在提交）。第 3 批的 19 项决策全部裁定；国际化双轨的收敛实现本身**不在第 3 批范围**，仍由 [#199](https://github.com/crane199709/saas-forge/issues/199) 承担（受 #193–#198 阻塞），本批只修正了把未完成写成已完成的表述。
>
> **时效边界**：本文是 `2dc1edb` 时点的快照。文中"真实情况"一栏描述的是当时状态，**其中不少已被第 1、2 批修复**。把它当作"当前事实"来读同样是错的——判断现状请按 [AGENTS.md](../../AGENTS.md) 的文档权威顺序取证。第七节标注了每批的落地情况。

---

## 一、结论

失效不是零散的，而是三次断层留下的系统性结果：

| 断层 | 时间 | 内容 | 被同步的文档 |
| --- | --- | --- | --- |
| ① Console 技术栈切换 | 2026-09-15（`8d4c570`、`3967013`、`e2267bd`） | 删除 React 页面、`@saas-forge/design-system`、`@saas-forge/react-shell`、`design-system-consumer-fixture` 及相关脚本/测试/视觉基线，改为 Vue 3 + Element Plus + Soybean Admin | 仅 `docs/25`、`26`、`27`、`console-testing-baseline.md` |
| ② 模块与命名统一 | 2026-09-14（`e93b79e`、namespace 改名） | `sdk/`→`saas-forge-sdk/`、`services/`→`saas-forge-services/`、`contracts/`→`saas-forge-contracts/`，包名 `io.saasforge`→`io.saas.forge` | `docs/` 头部路径大多已改，但 `.scratch/` 与个别代码块未改 |
| ③ 立项设计文档从未回填 | 2026-08-16 起 | `docs/00`–`docs/24` 的技术栈、API 分组、数据库表结构成型后未随实现更新 | — |

量化：

| 指标 | 数值 |
| --- | --- |
| 提到 React / Design System / Manifest 等已退出技术的文档 | 44 份 |
| 其中带正确"迁移前历史证据"标注的 | 4 份 |
| `docs/adr/` 中被后续 ADR 声明取代但**自身无前向标注**的 | 3 篇（0037、0042、0047） |
| `docs/acceptance/`（31 份）中带 UI 迁移失效标注的 | 0 份 |
| 手写文档相对链接损坏 | 8 处（全部在 `consoles/shared/README-en.md`） |
| 文档引用的 `scripts/*.sh|mjs|py` 失效 | 0 处 |

**最危险的三种噪音**（按被 AI 采信并据以行动的概率排序）：

1. **幽灵包与幽灵路径**——文档给出可直接执行的 `pnpm --filter @saas-forge/design-system …`、`pnpm --filter '...@saas-forge/react-shell' …`，指向 `src/app.tsx`、`src/remote.tsx`、`routes.tsx`、`shared/design-system/…`。照做必失败，或凭空重建已删除的包。
2. **幽灵 API 与幽灵表**——`docs/08` 的三个 REST 资源分组、`docs/11` 的六类表在实际契约与迁移中不存在。照做会产出无法路由的端点与第二套并行 RBAC 模型。
3. **失效的"通过"结论**——`docs/acceptance/issue-184` 的 Stage-2 主链唯一全绿证据（`adaaf43`）早于 Vue 切换，脚本定位器随后改成 Element Plus 却从未重跑。AI 会据此判定"当前已验收"。

---

## 二、判定标尺：当前权威基线

清单中所有"真实情况"以此为准。建议把这张表写进相关文档头部，防止再次漂移。

| 维度 | 当前事实 | 权威来源 |
| --- | --- | --- |
| 后端工具链 | JDK 17；Spring Boot **4.0.7**（parent）；Maven Wrapper 3.9.14 + SHA-256 | `pom.xml:9-11`、`.mvn/wrapper/maven-wrapper.properties` |
| 坐标与包名 | groupId `io.github.crane199709`；Java 包 `io.saas.forge.*` | `pom.xml:14`、各 `src/main/java/io/saas/forge/` |
| Maven 模块 | `gateway`、`saas-forge-services`、`saas-forge-contracts`、`saas-forge-sdk`、`saas-forge-quality-gates`（`test-support` 仅 profile 激活） | `pom.xml:51-56`、`:442-457` |
| 服务 | iam、tenant-access、entitlement、audit + service-discovery | `saas-forge-services/` |
| 契约 | `v1.yaml` 51 条 path，仅 `/api/v1/auth/*`、`/api/v1/platform/*`、`/oauth2/token`、`/.well-known/jwks.json`；生成 5 个 API client | `v1.yaml`、`saas-forge-sdk/public-api-allowlist.json` |
| 迁移链 | IAM V23 / Tenant Access V14 / Entitlement V7 / Audit V5 | `saas-forge-services/*/src/main/resources/db/migration/` |
| 前端技术栈 | Vue 3.5.31 + Element Plus 2.13.6 + vue-router 5 + Vite 8；Soybean Admin Element Plus（锁定上游） | `consoles/package.json`、`consoles/shared/admin/src/vendor/soybean/UPSTREAM.md` |
| 前端包 | `platform-console`、`tenant-console-shell`、`shared/admin`、`shared/api-client`、`shared/app-runtime`、`shared/i18n`、`business-remotes/admin-consumer-fixture`；**无** react / antd / design-system / react-shell | `consoles/pnpm-workspace.yaml` |
| 前端工具链 | Node 24.14.1、pnpm 11.22.0、Playwright Chromium | `consoles/package.json` engines |
| 本地开发 | 原生启动为目标；Compose 仅演示/集成验收 | `AGENTS.md:30-35`、ADR 0043 |
| 兼容范围 | 桌面 Chrome 当前稳定版 + JDK 17 | ADR 0046、`AGENTS.md:37-42` |
| 浏览器 Origin | 仅 `platform.<root>`、`console.<root>`；**无** `remote.<root>` | `ControlledBrowserCorsConfiguration.java:23-25` |
| 当前待办 | Issue #88、#103、#183–#200（其中 #190–#200 是 Soybean 迁移与收尾） | `gh issue list --state open` |

**根 `AGENTS.md` 与 `consoles/AGENTS.md` 的路径/脚本引用经实测全部有效**——噪音不在入口文件，而在它们引出的文档里。所以清理按"AI 会不会读到"排优先级，而不是按文件新旧。

**当前决策与历史噪音的分界**：`docs/adr/0051` 不是失效历史，而是**已决策但未实施**——`gh issue view 199` 显示 "删除自建 admin 与 i18n 并完成工作区收尾" 处于 OPEN + `ready-for-agent`。凡是与 #199 方向冲突的"现状描述"，都应标"待迁移"而不是删除。

---

## 三、P0：会直接让 AI 写错代码或得出错误"通过"

### A. Console / 前端所有权

| # | 来源 | 噪音内容 | 真实情况 | 处置 |
| --- | --- | --- | --- | --- |
| A1 | `docs/adr/0037`（:3,5,7,9,11） | 唯一 `@saas-forge/design-system` + Ant Design 6.6.2 + 共享 React Shell；Manifest 声明 Design System 版本 | 包已删除；AntD 已退出；无 Manifest 实现 | 加 `Superseded by ADR 0050` |
| A2 | `docs/adr/0047`（:7,15） | 扩展 `PageLayout`/`PageTitle`/`ServerTable`；"首批不新增多标签页、全局搜索、手动主题开关" | 组件已删除；platform-console **已实现** `global-tab`、`global-search`、`theme-settings`、`full-screen`（`src/layouts/modules/`） | 加 `Superseded by ADR 0050` |
| A3 | `docs/adr/0042`（:3,5,7） | Design System 是唯一品牌解析边界、React Shell 是唯一运行时应用缝 | 解析在 `consoles/shared/admin/src/brand/resolved-brand.ts`；ADR 0050 **未提及** 0042 | 加"包名由 0050/0051 更新，品牌原子应用决策仍有效" |
| A4 | `docs/adr/0039:3`、`docs/adr/0040:7` | 共享 React Shell 拥有 Provider/守卫/错误边界；Shell 独占语言选择 | Shell 已删除；由 `@saas-forge/admin` 承担；语言存在两套并存实现（见六.2） | 标注部分取代 + 记录收敛待办 |
| A5 | `docs/28-console-authentication-runtime.md`（:3,39,40,96,114,170,177,225） | 包所有权表写"共享 React Shell 包"与 `@saas-forge/design-system`（品牌唯一解析器）；React Error Boundary | 实为 `@saas-forge/admin`（Vue 3）；错误边界是 `onErrorCaptured`；品牌在 `brand/resolved-brand.ts` | **重写**（无历史横幅，被当现行规格） |
| A6 | `docs/29-console-internationalization.md`（:100-105） | "`DesignSystemProvider`…HTML `lang` 固定 `zh-CN`""两个 Console 尚未传入 Locale""夹具尚无 Locale 接口" | `documentElement.lang` 已动态设置（`shared/admin/src/runtime/context.ts:90`、`platform-console/src/locales/index.ts:56`）；`sf:ui:locale` 已有浏览器验收；夹具已是 `defineProps<{locale}>` | **重写该节** |
| A7 | `docs/29`（:118,119,167,168） | 契约表要求 `react-shell` 的 `ConsoleLocaleProvider`、`design-system` 的 `DesignSystemProvider.locale` | 真实接口是 `@saas-forge/admin` 的 `useLocale()` 与 `@saas-forge/i18n` 的 `resolveLocale`；组件语言由 `ElConfigProvider` 完成 | **重写契约表** |
| A8 | `docs/29`（:116,122,126,135） | `createTranslator(catalog, locale)`；"页面不直接依赖 ICU 库"；资源统一放 `src/locales/<Locale>.json` | 真实签名 `createTranslator({namespace, locale, messages})`；platform-console 直接引 `intl-messageformat` + `vue-i18n` 并复制匹配/回退逻辑；资源在 `src/messages/<module>/` | 重写；并把"两套 locale ref 不同步"记为技术债 |
| A9 | `docs/26`、`docs/27`（正文） | `design-system-consumer-fixture`；`test:boundaries` 拒绝 antd/design-system 选择器；制品门禁"单 CSS + SHA-256 相同"；四浏览器 CI 矩阵；`PageLayout`/`ResponsiveGrid`/`SplitLayout` 断点矩阵 | 夹具改为 `admin-consumer-fixture`；门禁是 `check-admin-boundaries.mjs`；制品门禁是 `verify-admin-artifacts.mjs`（无 CSS 哈希）；CI 只有 Linux Chromium；上述组件全不存在 | 顶部横幅已提示，**正文应删除或整篇归档** |
| A10 | `docs/local-verification.md`（:17,27,30,33,62） | "Design System 的包级 `verify`"；`--filter '...@saas-forge/react-shell'` 消费者查询；"Design System 国际化资源" | 两包均不存在；对应现代替为 `@saas-forge/admin` 与 `@saas-forge/i18n` | 点状改写（被引用 12 次，优先） |
| A11 | `consoles/shared/README-en.md`（全文） | 共享包为 api-client/app-runtime/react-shell/design-system；入口 `app.tsx`/`main.tsx`；`--filter @saas-forge/react-shell run verify`；夹具 `design-system-consumer-fixture` | 全部不存在；8 处相对链接已断 | **重写或删除**（对齐中文版） |
| A12 | `consoles/shared/app-runtime/README.md:25-26` | UI 由 `@saas-forge/design-system` 与"后续共享 React Shell"拥有 | UI 由 `@saas-forge/admin`（Vue）与两个 Console 拥有；与同文 `:5` 自相矛盾 | 改写一句 |
| A13 | `consoles/README.md`/`README-en.md`（:12,51,184,186 及目录/命令表） | "`/oauth-clients` 仍是占位入口"；英文版残留 "start the Design System showcase:" 断句；命令表写"Design System 测试/制品边界"；全文 0 次出现 i18n | `/oauth-clients` 已完整实现；无 showcase 脚本；门禁实为 admin 布局/消费者/会话；`shared/i18n` 是 workspace 包且被 `validate:i18n` 依赖 | 点状改写，目录表补 `shared/i18n` |
| A14 | `consoles/tsconfig.json:4,7` | 死配置 `"jsx": "react-jsx"`、`include: ["browser-test/**/*.tsx"]`（匹配 0 文件） | 仓库已无 `.tsx`；`check-admin-boundaries.mjs:79` 反把 `.tsx` 判为旧 UI 违规 | 删除 jsx 与 tsx include |
| A15 | `consoles/prototypes/design-system-foundation-prototype/` | ADR 0050 与计划称"原型已清除" | 磁盘上仍是 React/Ant Design 构建产物与依赖缓存（未跟踪） | 本地 `rm -rf`；如需留证移入 `.scratch/` |
| A16 | `consoles/AGENTS.md:18` | "新 Vue 模块的…验证通过 `pnpm --filter @saas-forge/admin run <脚本>`" | `@saas-forge/admin` 无 `dev`、无 `format:check`；应用包各自有 `verify`；`validate:i18n`/`build:workspace` 只能在 workspace 根跑 | 改为分层入口描述 |
| A17 | `consoles/.agents/skills/antd/`、`consoles/.codex/` | 无文档，属残留空目录 | `adaaf43` 新增、`8d4c570` 删除后遗留空目录，已被 glob/`ls` 误报为"存在 Ant Design 工具" | 删除空目录（并入 #199） |
| A18 | `deploy/acceptance/README.md:55`、`consoles/README.md:12`、`consoles/README-en.md:12` | "OAuth Client 管理、Tenant 创建、Quota/Plan、Subscription、Tenant 管理员初始化：尚无可操作的管理页面；平台 `/oauth-clients` 仅为占位入口" | 全部已实现：`platform-console/src/router/routes.ts:75-101` 注册了 `/oauth-clients`、`/new`、`/:id`、`/operations`，`ResourceList.vue:94` 调 `listOAuthClients`，另有 TenantCreate/PlanCreate/QuotaCreate 与 TenantDetails（含 Initialization/Lifecycle/Subscription 段），并被 `consoles/integration-test/console-vue-products.test.mjs` 真实断言 | 重写该表格行与 NOTE（严重度：高） |
| A19 | `deploy/acceptance/README.md:219` 及 EN 同处 | "默认本地验收还要求 Playwright Chromium、**WebKit** 与 Chrome" | `scripts/verify-console-authentication-e2e.sh:265-278` 只跑 product-chrome / console-browser-chrome；`consoles/scripts/verify-development-browser-matrix.mjs:13` 只有 chrome；ADR 0046 只承诺桌面 Chrome | 改为"仅 Chrome/Chromium" |
| A20 | `consoles/README.md:97` 及 EN 同处 | 把裸 `/password-setup` 与三个旧静态资源一起列为"精确转发到当前活动 Gateway" | `deploy/compose/local-https-development/edge.mjs:13-17,93-98` 的 passwordSetupPaths 仅含 `/password-setup/app.js`、`/password-setup/styles.css`、`/api/v1/auth/password-setups`；裸 `/password-setup` 由 Tenant Vite 提供 | 从清单删掉裸 `/password-setup` |
| A21 | `docs/local-verification.md:60` | Flyway 改动执行 `java script/FlywayMigrationGenerator.java validate` | 仓库**无 `script/` 目录、无该文件**（`find -iname` 零命中）；`docs/acceptance/issue-172:65` 已记录该命令无法运行并从 `AGENTS.md` 移除 | **删除**，改指向对应迁移的 Testcontainers 集成测试 / Fresh Compose 证据（照抄必报错） |
| A22 | `docs/native-entitlement-development.md:41-45` | `cd deploy/compose` 后 `docker compose run --rm --no-deps entitlement-migrate info\|migrate` | `entitlement-migrate` 只定义在 `saas-forge-services/entitlement-service/compose.yaml:3`（另 `deploy/acceptance/compose.yaml:76`）；`deploy/compose` 的服务仅有 redis/kafka/mailpit/nacos/nacos-init/otel-collector/postgres | 改为 `cd saas-forge-services/entitlement-service` 后执行（实测会报 `no such service`） |
| A23 | `docs/local-verification.md:24` | 单文件示例 `pnpm --dir consoles --filter @saas-forge/platform-console exec vitest run <file>` | platform-console 的测试是 `node --test ../integration-test/*.mjs`（`node:test`），包内无 vitest 用例；vitest 只用于 `shared/admin/test/*.test.ts` | 改为 `node --test <路径>`，或把示例包换成 `@saas-forge/admin` |
| A24 | `docs/local-verification.md:17` | "**Design System 的包级 `verify` 本身包含浏览器测试**" | `@saas-forge/admin/package.json` 的脚本只有 `typecheck`/`build`/`test`/`test:browser`/`lint`，**没有 `verify`**；浏览器测试是独立的 `test:browser` | 改为 `pnpm --filter @saas-forge/admin run test:browser`，并注明 admin 无 `verify` |
| A25 | `docs/native-local-development.md:28,32-35`、`native-console-development.md:7-16`、`local-static-remote-development.md:9-16` | 只需 Node 24.14.1 + pnpm 即可执行 `scripts/local-https-development.sh setup\|hosts\|trust-ca\|doctor` | `scripts/local-https-development.sh:7` 硬编码 `exec mise exec node@24.14.1`（`local-service-replacement.sh:6` 同），仓库无 `.mise.toml`/`.tool-versions`，文档从未提到 `mise` → 干净机器报 `mise: command not found` | 补 `mise` 前置条件，或去掉 wrapper 的硬依赖 |
| A26 | `docs/local-static-remote-development.md:37` | 升级步骤使用 `bash scripts/local-development.sh frontend start all` | 该命令会**托管启动 5173/5174 的 Vite**，与原生 `pnpm run dev` 互斥（`native-console-development.md:60`、`native-local-development.md:75`、`AGENTS.md` 均禁止混用），本页未提示 | 加"先停止原生 Vite"或改用 `local-https-development.sh start edge` |
| A27 | `docs/native-entitlement-development.md:35-37` | 迁移升级路径叙述停在 V6 / `plan_recovery` | 仓库已有 `V7__subscription_recovery.sql` 与 `subscription_recovery` 表 | 改为"迁移到当前最新版本（现为 V7）"，或把 V5/V6 段标为历史 ticket 记录 |

### B. 后端：幽灵 API 与幽灵 schema

| # | 来源 | 噪音内容 | 真实情况 | 处置 |
| --- | --- | --- | --- | --- |
| B1 | `docs/08-api-design.md:42,43,44,52` | `/api/v1/tenant`（Organization/Role/Permission/邀请）、`/api/v1/runtime`（Permission/Feature/Quota check）、`/api/v1/audit`（查询/导出）、`POST /api/v1/tenant/invitation-activations` | `v1.yaml` 51 条 path 中**零命中**；`v1.yaml:5-7` 自述 runtime 操作 "intentionally absent"；`CONTEXT-MAP.md` 声明无 Audit 公网路由 | 重写资源分组表，三行标"未实现的后续能力" |
| B2 | `docs/11-database-design.md:54`、`docs/10:51`、`docs/06:17` | `tenant_access_db` 含 `organizations`、`organization_units`、`permissions`、`role_permissions`、`membership_roles`、`invitations`、`capability_registrations` | 全部迁移**零命中**；实际是 `tenants`、`memberships`、`tenant_roles`、`membership_role_assignments`、`tenant_brand_profiles` | 重写表清单 |
| B3 | `docs/11:55`、`docs/07:5,29` | `plan_features`、`subscription_entitlement_snapshots` | 零命中；实际只有 `plans`、`plan_quotas`、`subscriptions`、`quota_definitions`、`quota_usages`、`quota_operations` | 重写 |
| B4 | `docs/11:55,91`、`docs/07:5`、`docs/adr/0004`、`entitlement-service/CONTEXT.md:15-16` | Subscription 版本化：`TRIALING`/`ACTIVE`/`SUPERSEDED` + 不可变权益快照 | `V3__create_initial_subscription.sql`：`CHECK (subscription_status = 'ACTIVE')`、`UNIQUE(tenant_id)`；无版本列、无快照表 | 标"目标设计（ADR 0004），当前仅 ACTIVE 单订阅" |
| B5 | `docs/11:53` | `iam_db` 用无前缀表名 + `signing_key_metadata` | 实际 `iam_*` 前缀；`iam_signing_keys`；邮箱列是 `normalized_email` | 重写 |
| B6 | `docs/11:18`、`docs/adr/0018:3` | 所有表 `DEFAULT uuidv7()`，应用插入不传 `id` | 仅部分表有默认值；`subscriptions`、`tenant_roles` 无默认值且 Mapper 显式传 id（`EntitlementBootstrapMapper.xml:100-103`） | 补出例外：ID 须在事务开始前确定时由应用生成（幂等目标、Outbox 聚合引用） |
| B7 | `docs/02:85`、`docs/11:93` | Audit Record 含 Membership/Request ID/IP/UA；按 tenant/时间/Action/Identity/Resource 建索引 | `V2__create_session_started_audit_records.sql` 无这些列；`action`/`resource_type`/`result` 被 CHECK 限死；除唯一约束外无二级索引。**`docs/11:56` 又禁止伪造这些字段，与 `docs/02:85` 直接冲突** | 重写并消解两文冲突 |
| B8 | `docs/07:29`、`docs/08:43,91`、`docs/09:63` | Quota 提供 `check`/`usage`；"`check` 不需要 operationId" | `quota_command.proto` 只有 `Consume`、`Release`；`V4` 有 `CHECK (quota_code='max_users')`、`CHECK (amount=1)` | 重写 |
| B9 | `docs/08:115`、`docs/12:93` | Gateway 基于 Redis 令牌桶按 IP/Identity/Client/Tenant 限流 | 23 个 gateway 类中无限流 Filter，配置无此项；仅 `redis/registry/gateway.json` 登记了未实现的 key | 标"已登记未实现" |
| B10 | `docs/01:74-79`、`docs/02:89-91`、`docs/09:57` | `@RequireFeature`、`@RequirePermission`、`audit.log(...)` 可用 | `sdk-permission/-feature/-quota/-audit` **只有 pom.xml，0 个 Java 文件**，不在 allowlist | 标为未实现占位 |
| B11 | `docs/09:46-48` | `TenantContext.getTenantId()`、`MembershipContext.getMembershipId()` | 真实是 `TenantContextAccessor.requireCurrent()` → `TenantContextSnapshot(identityId, membershipId, tenantId)`、`IdentityContextAccessor.current()`；无 `MembershipContext` 类 | 用真实 API 重写代码块 |
| B12 | `docs/03:8` | Spring Boot **4.1.x** | parent 是 **4.0.7** | 改为"跟随根 POM"以免再漂移 |
| B13 | `docs/03:15`、`docs/08:83`、`docs/11:107`、`docs/adr/0023:3` | S3 对象存储、导出任务资源、`export_jobs` 表 | 全仓库无对象存储依赖/镜像/实现，无 `export_jobs`；`deploy/acceptance/README.md:19` 明确"第 6 阶段加入" | 标未实现 |
| B14 | `docs/03:17`、`docs/14:97` | Prometheus/Loki/Tempo/Grafana 可观测栈与 OTel 导出 | 仅部署 `otel/opentelemetry-collector`（debug exporter）；`deploy/acceptance/README.md:19` 明确不部署其余 | 标未部署 |
| B15 | `docs/03:18`、`docs/04:34`、`docs/14:7` | Compose 是本地开发入口（`docker compose up -d` 得到控制台） | ADR 0043 + `AGENTS.md` 规定原生启动为目标，Compose 仅集成验收 | 改写为集成验收工具 |
| B16 | `docs/01:19-25` | Platform/Tenant Console 菜单含 Feature 管理、平台管理员/角色、系统设置、审计日志、组织架构、权限管理 | 真实路由只有 `/`、`/tenants`、`/tenants/:id`、`/plans`、`/quota-definitions`、`/oauth-clients`、`/oauth-clients/operations`；Tenant 端只有 `/` | 标为未实现范围 |
| B17 | `docs/10:14-20,26-27,44`、`docs/09:7-18` | 把 `contracts/redis`、`contracts/logging` 当 Maven 模块；漏 `security`/`services`/`compatibility-baselines`；`spring-boot-starter` 画在 `saas-forge-java` 下 | 各 pom 的真实模块列表不同；starter 在 `saas-forge-sdk/saas-forge-starters/` | 点状修正 |
| B18 | `docs/10:70-74`、`docs/adr/0037:7` | 业务模块以 Module Federation Remote 独立构建部署；Manifest 版本治理协议 | 无任何 MF/Manifest 实现；`consoles/README.md:12` 称"尚未接入"；官方业务 Remote 不存在 | 整节标 superseded |
| B19 | `docs/01:36`、`docs/10:33`、`docs/11:98` | `examples/` 有官方 Example 与独立 Flyway 迁移链 | `examples/` 只有 README，无源码无迁移 | 标未实现 |
| B20 | `docs/06:5`、`docs/02:37-46` | IAM 含 "API Key"；产品元数据 `saas-forge.product.code/name` | 只有 OAuth2 Client Credentials；`saas-forge.product` 全仓库零命中 | 删除或标未实现 |
| B21 | `docs/adr/0031:15`（另 :9,13） | Tenant Context Switch "当前服务端尚未实现，只会落到生成接口的默认 `501`"；Gateway/浏览器"不属于本切片" | `AuthenticationController.java:241` 已实现 `switchTenantContext`；V15–V17 迁移与 `iam_tenant_context_switches` 表存在；错误码齐备；`docs/32:68` 已含浏览器侧验收 | 重写为已完成事实 |
| B22 | `docs/adr/0033:3`、`docs/22:19` | "MVP 只增加单 Client 详情/创建/轮换/恢复/吊销，**不提供列表**" | `v1.yaml:1649` 有 `listOAuthClients`，`OAuthClientsController.java:67` 已实现，`ResourceList.vue` 已消费；`issue-178:7` 记录交付 | 改为"后续增量交付，见 Issue #178" |
| B23 | `docs/adr/0025:25` | Gateway "只生成" 7 个 Problem code | 实际 9 个：多出 `BROWSER_REQUEST_REJECTED`、`ACCESS_TOKEN_SCOPE_INSUFFICIENT`（`GatewayProblemDetailsWriter.java:53,55`，`switch` 对未知 code 抛异常） | 补入两个 code |
| B24 | `docs/adr/0022:3`、`docs/12:80` | "Tenant 表**始终**启用并强制 RLS" | `tenant_creation_idempotency`（`V3__create_pending_tenant.sql:5,9`）、`subscription_recovery`（`V7:2,6`）带 `tenant_id` 却无 RLS 与维护策略 | 明确 Tenant RLS 适用范围与幂等/恢复表例外 |
| B25 | `docs/adr/0034:9` | 动机段称"**现有**三处硬编码（`Target` 枚举、owner switch、手写白名单）已形成漂移风险" | 这些机制已由本 ADR 移除（`git log -S "enum Target"` → `b469d38`）；现路由完全由 `GatewayRouteCatalog` 驱动 | 改为过去时 |
| B26 | `docs/17:305` | 错误码 `TENANT_ADMIN_INITIALIZATION_REQUIRED` | 全仓库零命中；实际为 `NOT_FOUND`/`IN_PROGRESS`/`COMPENSATING`/`RETRY_REQUIRED`/`DEPENDENCY_UNAVAILABLE` | 改为真实枚举 |
| B27 | `docs/18:33` | "安全前置完成前不得提交 `SUSPENDED`" | 冻结/恢复已交付（`v1.yaml:861,902,933`、V19/V20 迁移、`docs/16:155,171`） | 更新为已解除的前置条件 |
| B28 | `docs/22:265` | `./mvnw … -pl quality-gates -am verify` | 模块名是 `saas-forge-quality-gates`；原命令报 "Could not find the selected project in the reactor" | 修正命令 |
| B29 | `docs/12:111`、`docs/13:106`、`docs/14:105`、`docs/13:107` | CI 执行依赖/镜像漏洞扫描、OWASP ZAP、Helm 验证；标签发布含 Helm Chart | `.github/workflows` 全量 grep `zap|trivy|vulnerab|k6|helm` **无命中**；`verify.yml` 只有 4 个 job；`deploy/helm` 无 Chart；`release.yml` 只发 Maven | 标未实现 |
| B30 | `docs/20:3` | 日志统一输出结构化 JSON | 无任何 logback/logstash/`logging.structured` 配置实现 | 标未实现 |
| B31 | `docs/13:16` | 前端测试用 Vitest + **React Testing Library** | 无 testing-library 依赖；边界/集成用 `node --test`，组件用 vitest browser + `@vitejs/plugin-vue` | 重写 |
| B32 | `docs/13:37-48`、`docs/16:141` | 视觉矩阵 4 类状态 × 双语 × 明暗 × 5 视口；已建 Playwright 基础设施 | 现存仅 8 张快照（布局 1440/1024、认证 1280）；无 `playwright.config` | 重写 |
| B33 | `docs/15:15-17` | 把 OAuth 2.0 与 Event 列为 Phase 3 未来项 | 均已交付；且 `docs/15` 的 Phase 0–4 与 `docs/16` 的 0–9 阶段两套编号并存 | 合并阶段口径 |
| B34 | `docs/16:126-134` | `[x]` 勾选"唯一共享 Design System 包""Ant Design 6.6.2 作为底层组件基础""共享 React Shell"、"五浏览器 Fresh Compose 聚合验收" | 全部已被 ADR 0050/0051 推翻；浏览器矩阵已由 ADR 0046 收缩 | 撤销勾选并改写 |
| B35 | `docs/21:34` vs `:49` | 对空 SDK 占位模块的覆盖率要求自相矛盾 | 四个 SDK 模块无源码，无法产出覆盖率 | 明确空模块的处理规则 |
| B36 | `docs/12:103`、`docs/18:77` ⚠️待裁定 | 事件类型 `com.saas.forge.iam.sessions-revoked.v1` | `UserSessionsRevokedEventFactory.java:13` 真实发布该事件，但既无 payload schema 也未进 `engineering-registry.json`（而 CI 声明注册表是权威） | **需你裁定**：补登记还是改类型名 |
| B37 | `docs/adr/0003:3` | "**所有**外部可见的创建和状态变更请求必须携带 `Idempotency-Key`" | 登录/登出在契约中明确**忽略**该头（`v1.yaml:90`、`:279-284` deprecated）；refresh/password-changes/context-selections 无该参数；ADR 0026:3 明确"登录和登出不使用通用的 HTTP 幂等记录" | 收窄为"业务资源创建/状态变更"并内联指向 0026 |
| B38 | `docs/adr/0003:3` | "24 小时后同一键可视为新请求"、"原样重放响应体" | 已被 0033:7（OAuth Secret 为窄安全例外、不重放 Secret）与 0045:7（过期请求行为收紧）收窄；`v1.yaml:1455` 与 `RecoverablePlanService.java:57`（`PLAN_RECOVERY_EXPIRED`）已实现收紧 | 标注局部 superseded，链接 0026/0033/0045 |
| B39 | `docs/adr/0004:3` | 套餐变更/重新订阅时标记 `SUPERSEDED` 并写入新的**不可变权益快照** | `SubscriptionStatus.java:3-5` 只有 `ACTIVE`；`V3` 为 `tenant_id UNIQUE` + `CHECK (= 'ACTIVE')`；无快照表；额度上限**实时**从 Plan 读（`EntitlementBootstrapMapper.xml:152-158` JOIN `plan_quotas`）；`docs/16:244-245` 列为阶段 5 未完成 | 标"阶段 5 目标模型"，与 `docs/17:59,75,89` 一并处理 |
| B40 | `docs/adr/0006:3` | Invitation 激活 Saga 与 `503` / `INVITATION_ACTIVATION_COMPENSATING` | 全仓库无该错误码、无 `invitations` 表、无 Invitation 代码；`v1.yaml` 无该 path；`docs/16:216-217` 列为未完成。已实现的同类流程是"初始租户管理员初始化"（`TenantCreationExceptionHandler.java:88`） | 标"阶段 4 未实现" |
| B41 | `docs/adr/0008:3` | 生产 JWT 由 KMS/HSM 签名 | 只有 `PemJcaJwtSigningAdapter.java`；唯一 Bean 受 `JwtSigningConfiguration.java:54-55` 的 `pem-jca` 条件约束；无 AWS/KMS SDK 依赖；`docs/12:88` 已说明"prod 缺少外部适配器 Bean 时拒绝启动" | 在 0008 内联指向 `docs/12:88` |
| B42 | `docs/adr/0015:3` | OpenAPI 是 `sdk-core` Java REST Client 的**唯一**输入 | 现行规则是仅发布显式声明 `x-saas.forge-java-sdk: true` 的 operation（ADR 0041:3）；浏览器会话类 operation 不得获得标记（契约 README:9）；0015 全文无指向 0041 的说明 | 内联标注"由 ADR 0041 收窄为显式安全子集" |
| B43 | `docs/adr/0016:3,5` | "v1 不设兼容性豁免"，除 0031/0038 外无例外 | 门禁内至少还有两处已批准例外：`V1ContractCompatibilityTest.java:401`（#170/#174 批准的 `plans.limit.minimum` 0→1，仅记录在 `compatibility-baselines/README.md:7-9`）、`.github/workflows/console-authentication-e2e.yml:35-39`（ADR 0048 授权的基线重建哈希放行） | 增补已批准例外清单并指向 `compatibility-baselines/README.md` |

### C. 失效的验收结论（最容易让 AI 宣布"已通过"）

| # | 来源 | 噪音内容 | 真实情况 | 处置 |
| --- | --- | --- | --- | --- |
| C1 | `docs/acceptance/issue-184-stage2-main-chain.md:79` 及 `evidence/issue-184/r18/*` | "第十八轮 `--stage2-product` 退出码 0，完整产品与 Chrome 门禁通过" | r18 证据 `commit=adaaf43` **早于** `8d4c570`；同提交把主链定位器从 `.ant-select-*`/`.sf-form-field` 改为 `.el-select-*`/`.el-form-item`，切换后**从未在真实环境重跑** | **最高优先**：文首与 r18 章节标注时效边界 |
| C2 | `docs/acceptance/` 全目录（31 份） | 全部以"当前实现/通过"语气书写 | 仅 3 份带 ADR 0046 兼容矩阵提示，**无一份**提到 ADR 0050/0051 的 UI 替换 | 批量加统一失效横幅（沿用 `docs/26` 措辞） |
| C3 | 12 份验收记录（`console-tenant-ui-a`、`console-login-ui`、`issue-125`、`issue-147`、`issue-180`、`issue-181`、`issue-184`、`issue-115`、`issue-153`、`issue-162`、`issue-172`、`issue-174`） | 引用 `shared/design-system`、`shared/react-shell`、`browser-test/*.browser.test.tsx`、`app.tsx`/`main.tsx`、`design-system-boundaries.test.mjs`、"Design System 90 / React Shell 123" 等门禁计数 | 这些包/文件/门禁已全部删除；`verify:workspace` 实际不含这些计数 | 加失效标注；`console-tenant-ui-a.md`、`console-login-ui.md` 建议整体归档 |
| C4 | `docs/acceptance/issue-180-shared-testing.md:16-19,82` | "94 个组件 + 40 个消费者测试""129 张权威基线""`{components,consumers}.json`" | 组件阶段已随 design-system 删除；`run-console-visual-container.sh` 现只产出 `consumers.json`；现存基线仅 `admin.browser.test.ts/` 8 张 PNG | 加失效标注 |
| C5 | `docs/acceptance/assets/`（38 个文件）与 `issue-147/172/173` 截图 | 作为当前视觉验收基线 | 目录链接全部有效，但内容是 React/Ant 界面；`console-testing-baseline.md:3` 已声明"不以旧截图作为新界面基线" | 加"迁移前历史证据"说明或移入 `archive/` |
| C6 | `issue-168:73-106`、`issue-159:69-100`、`issue-180:44` | 以 `.scratch/issue-{158,159,168}` 等本地临时目录为证据 | 目录已不存在，且被 `.gitignore` 忽略、Git 无跟踪 | 改为"本地临时证据，不可复现" |
| C7 | `console-login-ui.md:7,21` ↔ `issue-191:25,31` | 两代登录页描述都当现状（共用 `LoginLayout` + 右上角入口角标 vs 官方波浪背景 + 移除演示入口） | 前者属已删除的自建壳 | 标注失效并指向 `issue-191` |

### D. ADR 悬空取代链

- `docs/adr/0050:7` 单向声明"替代 ADR 0037 与 ADR 0047"，但 **0037 / 0042 / 0047 文件内没有任何 superseded 标注**（对照正面样例 `docs/adr/0012:5` 已正确标注被 0048 替代）。
- `docs/agents/domain.md` 要求 AI 探索前读取"相关 ADR"——检索式工作流很可能只命中 0037/0047，把 AntD/Design System/React 约束当现行规范重新实现。
- 处置：0037、0047 加 `Superseded by ADR 0050`；0042 加"所有权名称更新、决策仍有效"；同时修正 `docs/16` 的 `[x]` 旧条目。

### E. "项目成熟度叙事"仍停在起点

| # | 来源 | 噪音内容 | 真实情况 | 处置 |
| --- | --- | --- | --- | --- |
| E1 | `ROADMAP.md:5` | "当前完成的是工程骨架，后续从领域模型验证开始" | 已有 Gateway + 4 个领域服务 + SDK/Starter + 契约 + 两个 Vue Console；迁移链 IAM V23 等 | **重写**（严重度：高） |
| E2 | `CHANGELOG.md:7-9` | `Unreleased → Added` 唯一条目是"初始化 Maven 多模块项目骨架" | 全文 20 行、2 个条目，实际 297 commits | 汇总实际里程碑或标注"未回填"（严重度：高） |
| E3 | `SECURITY.md:5` | "当前仓库处于未发布的项目骨架阶段" | "未发布"为真（无 tag），"骨架阶段"已过时 | 只删"项目骨架阶段" |
| E4 | `README.md:44` | "通用 Tenant RBAC、Feature 运行时闭环和 Audit 业务能力仍未完整实现" | Audit 已有 38 个 Java 文件、V2–V5 迁移、Kafka 消费与隔离重放、e2e 脚本；RBAC/Feature 确实仍不完整 | 只更新 Audit 部分 |
| E5 | `README.md:72,74` | "业务 Remote"、`deploy/` 是"Compose、Helm 与 systemd 交付物的**预留位置**" | `business-remotes/` 只有 fixture；`deploy/compose`、`nacos/{dev,test,staging,prod}`、`acceptance`、`docker` 均已生效（`validate-nacos-config.sh`、`validate-compose-layout.py` 实测通过），仅 `systemd`/`helm` 属预留；ADR 0049 已确认归属 | 分开表述（严重度：中） |
| E6 | `README.md:54-62` | 快速开始只到 `pnpm install --frozen-lockfile` + `./mvnw verify` | `./mvnw verify` 会经 `verify-frontend-workspace.sh` 跑到 `test:browser:chromium`，需要 Playwright Chromium | 补 `pnpm exec playwright install chromium` |
| E7 | `CONTRIBUTING.md:19` | "通过 Pull Request 合并到 main" | 默认分支是 `master`（`git symbolic-ref`、`git remote show origin`；无 `main` 分支）；`publish-nacos-config.yml:36-38` 强制 `refs/heads/master` | 改为 `master` |
| E8 | `AGENTS.md:54` | "**Design System** 自有消息资源必须按可独立 tree-shake 的组件模块拆分…" | 主体 `shared/design-system` 已删除；脚本与命令仍有效；当前目录为 `shared/admin/src/messages` 等 | 改按当前包名，或待 #199 后改写为模板 i18n 机制 |
| E9 | `README.md:78`、`deploy/acceptance/README.md:19` 及 EN 同处 | 对象存储"将在**第 6 阶段**加入" | ADR 0036:5 明确"第 **4** 阶段随 Tenant 品牌引入最小 S3 能力，并与第 6 阶段的 Audit 导出使用分离存储边界"；`docs/16:121,223` 已同步 | 标 superseded，统一为"第 4 阶段引入、第 6 阶段复用"（严重度：中） |
| E10 | `README.md:78` | 最小拓扑 = Gateway + 四服务 + PostgreSQL + Redis + Kafka + OTel + 四个迁移任务 | `deploy/compose/compose.yaml:70-74,85-104` 与 `deploy/compose/README.md:5` 还含 **Mailpit** 与 **Nacos + nacos-init**；Nacos 是服务发现必需项 | 补齐（否则 AI 搭出的拓扑无法启动） |
| E11 | `README.md:69`（含 `:66-74` 整个目录清单） | `saas-forge-services/`：IAM、Tenant Access、Entitlement 与 Audit | `saas-forge-services/pom.xml:23-27` 还有 `saas-forge-service-discovery`；根 `pom.xml:52-56` 还有 `saas-forge-quality-gates`，`:449,455` 以 profile 引入 `test-support` | 补齐模块清单 |
| E12 | `deploy/acceptance/README.md:10` 及 EN 同处 | "本目录提供…最小本地运行拓扑，**供开发**、演示和端到端测试使用" | 同文 `:3`/`:6`、`scripts/README.md:3`、`deploy/README.md:5`、ADR 0043/0049 与根 `AGENTS.md` 都把原生开发列为日常入口，完整 Compose 不得作为必经步骤 | 删掉"供开发" |
| E13 | `deploy/acceptance/README.md:413-423` 及 EN 同处 | "`.env.example` 包含所需变量名"（只列 PG 管理员、四服务、Redis、Nacos） | `deploy/acceptance/.env.example:33-38` 还有 6 个 `KAFKA_*` 变量，被 `tenant-lifecycle-e2e.override.yaml:17-47` 与 `deploy/compose/kafka-audit-acl-init.sh:5-6` 消费 | 补 Kafka 行或注明仅用于 SASL/ACL 覆盖场景 |
| E14 | `scripts/README.md:10` | `local-development.sh frontend <start\|status\|stop> platform` | `scripts/local-development.mjs:26-38` 接受 `platform\|tenant\|all`；`deploy/acceptance/README.md:122-131` 已列 9 条命令 | 同步为 `platform\|tenant\|all` |

---

## 四、P1：整篇/整目录系统性失效

| 来源 | 性质 | 建议 |
| --- | --- | --- |
| `docs/00`–`docs/11`（12 份） | 定位与边界仍有效，但技术栈/API/DB 三块系统性失真 | 逐篇重写 P0 条目，头部加"以 `v1.yaml` / Flyway 迁移 / `consoles/*/package.json` 为权威"指针 |
| `docs/12`–`docs/24`（13 份） | 同上；状态行（`docs/22`/`23`/`24`）与"当前差距"是实施前快照，前端与测试部分停留在 React 时代 | 重写状态行与差距清单；`docs/13` 重写测试矩阵 |
| `docs/16-mvp-development-plan.md` | 52KB 计划，勾选状态与第 7 阶段（Manifest/Remote 治理）大量过时 | 重写"当前起点"与勾选；第 7 阶段标"未实现/暂缓" |
| `docs/15-roadmap.md` + `ROADMAP.md` | 阶段编号与完成度过时 | 与 `docs/16` 统一口径 |
| `docs/26`、`docs/27` | 顶部有横幅，但正文仍以现在时陈述已删除的门禁契约 | 整篇归档或删除，保留指针 |
| `docs/acceptance/`（31 份） | 见 C2 | 批量横幅 + `archive/` |
| `docs/adr/0037/0042/0047` | 见 D | 加取代标注 |
| `consoles/shared/README-en.md` | 整份旧架构 | 重写或删除 |
| `.scratch/`（整体未跟踪、未被完整忽略） | 含已关闭 issue #181 的 PRD 逐字副本、路径已失效的对比报告（引 `services/`、`sdk/`、`io.saasforge`、React/Design System） | 在 `AGENTS.md` 声明 `.scratch/` 为只读历史归档、非权威；或整体加入 `.gitignore` |

---

## 五、P2：点状修正

- `docs/25-design-system.md:10` 抽屉清单不全（`quota-detail`、`oauth-detail`、`oauth-create`、`quota-create` 也是抽屉）。
- `docs/28:96` 的 20s/5s Worker 内容正确，只是归属包名错。
- `docs/30:21` 证据入口 `platform-console/src/routes.tsx` → `src/router/routes.ts`；`docs/30:101`、`docs/31:133` 的"复用现有设计系统" → "`@saas-forge/admin` + Element Plus"。
- `docs/console-testing-baseline.md:8` 把 `pnpm --dir consoles run test` 描述为"纯 Runtime/API/品牌"，实际含边界门禁与 Playwright 产品路由回归。
- `docs/local-verification.md`（见 A10）。
- `consoles/README.md`/`README-en.md` 的 i18n 遗漏与 showcase 断句（见 A13）。
- `docs/adr/0050:9` 提到的"先前接入的 Skyroc 代码和新增依赖"在仓库中不存在（`git log --all -S Skyroc` 只命中文档）。
- `docs/adr/0019`/`0020` 的生产部署前 Job 与"生产等价引导流程"：本地 Compose 侧成立，生产侧无 Helm Chart/Job 制品（`deploy/helm/README.md:3` 自述）。**待确认**是待实现契约还是有意留空。
- `docs/adr/0025:23` "不得回退到静态服务地址" vs `LocalGatewayReplacementLoadBalancerConfiguration`（`saas.forge.local-replacement.enabled=true` 显式开启的本地替换工具）。**待确认**是否需要写成显式例外。
- `docs/13:106` 写 `main` 分支（应为 `master`）；`docs/14:9,112` systemd 仅占位；`docs/16:169` 引用远端 CI run 无法本地核验。
- `consoles/.agents/skills/antd/`、`consoles/.codex/` 空目录。
- 根 `README-en.md` 缺失（其他 README 均有英文版）。**待确认**是否有意。
- `gateway/README.md:6` 等 5 个文件共 11 处使用裸 `mvn` 而非仓库要求的 `./mvnw`（另见处置顺序第 9 条）。

---

## 六、不是噪音，但需要你决策

1. **ADR 0051 的落地范围**。`gh issue view 199` = OPEN + `ready-for-agent`（"删除自建 admin 与 i18n 并完成工作区收尾"），说明这是**已决策待实施**。但 0051 正文用了完成时语态，且其中"两个 Console 均基于官方应用结构"目前只对 platform-console 成立（tenant-console-shell 仍 `mountConsole` + `Workspace.vue`）。
   → 建议：在 0051 加 `状态：已确认，未实施（Issue #199）`，拆出"platform 端已实施 / tenant 端待实施"，并在 `consoles/AGENTS.md`、`consoles/shared/README*.md`、两个应用 README、重构计划中标注"待迁移"。
2. **国际化双轨**。platform-console 已用模板 `vue-i18n`（`src/locales/index.ts` 直接引 `intl-messageformat`），tenant-console-shell 仍用 `@saas-forge/i18n`；两者写同一个 `sf:ui:locale` 键却持有独立 ref，不共享状态。这是真实缺陷，也是 #199 要解决的问题。
   → 需要确认收敛方向与时间点。
3. **`com.saas.forge.iam.sessions-revoked.v1`**：生产代码确实发布该事件，但无 schema、未进 `engineering-registry.json`，而 CI 又声明注册表是权威（见 B36）。
   → 需要裁定补登记还是改类型名。
4. **平台品牌权威来源**。租户级品牌在 `tenant_brand_profiles`；平台默认品牌落在 `consoles/shared/admin` 构建期常量还是配置，无法从代码确证。
5. **`docs/23`、`docs/24` 状态行仍写"等待最终 `ok` 后实施"**，但 Gateway 路由目录模块（`saas-forge-http-route-catalog`，`deploy.skip=false`）与 Audit 成功事实消费（V2–V5 + e2e）均已实现。
   → 需要确认改为"已实施"还是保留为设计规格并标注实现进度。
6. **阶段编号两套并存**：`docs/15` 的 Phase 0–4 与 `docs/16` 的 0–9 阶段。
   → 需要指定唯一权威口径。
7. **"本地配置模板"方向相反**（必须先裁定，不能局部补一句）。`AGENTS.md`「本地开发」要求"**提供可提交的本地配置模板**"，`docs/acceptance/issue-169:1,11,50` 也把"总入口列出五份模板""核实五份个人 YAML 的 Git ignore"记为验收证据；但六份 native-* / `development-configuration.md:11` 都写"不再保留/不再复制个人配置模板"。同时四个服务的 `pom.xml` 仍在排除 `application-local.yml.example`（如 `gateway/pom.xml:84`），而仓库中已无任何本地配置模板文件。
   → 需要裁定模板是否仍为验收项：保留则补模板路径并同步 `pom.xml`；取消则同步修改 `AGENTS.md` 与 #169 记录。
8. **`@saas-forge/admin` 与 `@saas-forge/api-client` 没有 `verify` 脚本**。`scripts/verify-frontend-workspace.sh:50` 有一条 `--fail-if-no-match --filter <package> run verify` 分支；默认（无参数）走 `pnpm run verify:workspace` 故 CI 不受影响，但定向调用 `verify-frontend-workspace.sh @saas-forge/admin` 会直接失败。
   → 需要确认是补脚本还是把该分支限定到有 `verify` 的包。

---

## 七、建议处置顺序

> **落地情况**：第 1 批 → `cdba104`（34 文件）；第 2 批 → `273e4a2`（72 文件）；第 3 批 → `bc69d16`（浏览器断言与共享包门禁）、`0ee459e`（事件登记与一致性门禁）与本文所在提交（文档收口）。以下是原始建议清单，保留原文以便对照。

**第 1 批（半天，消除"会被直接照做"的风险）—— 已落地 `cdba104`**
1. `docs/adr/0037`、`0047` 加 `Superseded by ADR 0050`；`0042` 加所有权更新说明；`0031:15`、`0033:3`、`0003`、`0015`、`0016`、`0004` 按 B 表改写或加标注。
2. `docs/28`、`docs/29` 重写包名与"当前仓库事实"两节。
3. `docs/08:35-44` 与 `docs/11:53-55` 按 `v1.yaml` 与 Flyway 迁移重写（或整表标"设计目标，未实现"）。
4. `docs/local-verification.md`、`consoles/README.md`、`consoles/shared/README-en.md`、`deploy/acceptance/README.md:55` 的 `react-shell`/Design System/"占位入口"改写；并删掉四条照抄必失败的命令（A21 `FlywayMigrationGenerator`、A22 `deploy/compose` 下的 `entitlement-migrate`、A23 vitest 跑 `node:test`、A24 admin 的 `verify`）。
5. `docs/acceptance/issue-184` 加时效边界声明。
6. `ROADMAP.md`、`CHANGELOG.md`、`SECURITY.md:5`、`CONTRIBUTING.md:19` 的成熟度叙事与分支名；`README.md:69-78` 的模块清单、拓扑与对象存储阶段。

**第 2 批（1–2 天，结构性去噪）—— 已落地 `273e4a2`**
7. `docs/acceptance/` 批量加统一失效横幅；`console-tenant-ui-a.md`、`console-login-ui.md`、`docs/26`、`docs/27` 移入 `archive/` 或删除。
8. `docs/00`–`docs/24` 头部加权威指针；重写 `docs/03`、`docs/08`、`docs/10`、`docs/11`、`docs/13`、`docs/16` 的 P0 条目。
9. 清理 `consoles/prototypes/`、`consoles/tsconfig.json` 死配置、`consoles/.agents/skills/antd/`、`consoles/.codex/`；把 11 处裸 `mvn` 改为 `./mvnw`（`docs/native-{audit,entitlement,platform-auth,tenant-access}-development.md`、`gateway/README.md`）。
10. 在 `AGENTS.md` 声明 `.scratch/` 语义；`consoles/AGENTS.md:18` 改分层入口。

**第 3 批（19 项决策已全部裁定并落地）—— 已落地 `bc69d16` + `0ee459e` + 文档收口提交**
11. 明确 ADR 0051 / #199 的落地计划，同步全部现状文档。→ ADR 0051 加"已确认未实施"状态行与双向衔接；ADR 0037/0039/0040/0050 补 0051 指针；重构计划新增「未完成项与 #199 验收对齐」，向 #199 的六条 AC 对齐；`docs/29` 新增「未达成项」并修正"两个独立 Locale 引用"的错误结论。
12. 收敛国际化双轨、裁定 sessions-revoked 事件、统一阶段编号。→ 阶段编号统一为 `docs/16` 的 0–9（`docs/15` 只保留 MVP 之后的 H1/H2，`ROADMAP.md` 与 `docs/16:17` 同步）；`iam.sessions-revoked.v1` 与门禁新发现的 `tenant.administrator-initialized.v1` 一并登记，并新增"产出必须登记"的一致性门禁；国际化**双轨收敛未实施**，按 Q9 保留 ADR 0051 的 vue-i18n 方向，只把不可达成的 AC1 措辞修正为"删除翻译运行时、保留框架无关的语言注册表与精确格式化 API"。

**防复发建议（部分未采纳，见下）**
- 统一惯例：整篇失效的在首行加"本文保留迁移前历史证据；当前实现见 X"；局部失效的行内标注——**不要只留正文不改**。
- ADR 替代关系要求**新旧两侧都写**（0050 已做，0037/0042/0047 缺）。
- CI 增加轻量检查：扫描 `docs/` 中引用的包名/路径是否存在（可直接复用本次的机械扫描），防止再出现幽灵包与幽灵文件。→ **未采纳**：`docs/acceptance/` 与 `docs/archive/` 大量合法引用已删除的路径，按存在性扫描会大面积误报。第 3 批只落地了可精确判定的"服务产出的事件必须登记"门禁（`RepositoryStandardsTest.producedEventTypesAreRegisteredInEngineeringRegistry`）。

---

## 八、审计覆盖、方法与未验证项

**已覆盖（11 个切片全部完成）**
- `docs/adr/` 全 51 篇（0001–0017、0018–0035、0036–0051 三段各逐篇核对）
- `docs/00`–`docs/32`（43 份）
- `docs/acceptance/`（31 份）+ `docs/plans/`（1 份）+ `evidence/`、`assets/`
- 本地开发文档（6 份 `native-*`、3 份 `local-*`、`development-configuration.md`）
- 运维文档（`deploy/**`、`scripts/README.md`、`gateway/README.md`、`examples/`）
- `consoles/` 全部手写文档、包结构、脚本、`tsconfig`
- 入口文件：`AGENTS.md`、`CONTEXT-MAP.md`、6 个 `CONTEXT.md`、`README.md`、`ROADMAP.md`、`CHANGELOG.md`、`CONTRIBUTING.md`、`SECURITY.md`、`.scratch/`

**机械扫描结论**
- 手写文档相对链接：146 个文件、8 处损坏，全部集中在 `consoles/shared/README-en.md`。
- 文档引用的 `scripts/*.sh|mjs|py`：无失效引用。
- ADR 取代声明：0012/0016 双向正确；0037/0042/0047 缺前向标注。

**已核验一致、不建议改动**
`docs/adr/0001`、`0002`、`0005`、`0007`、`0009`、`0010`、`0011`、`0012`、`0013`、`0014`、`0017`、`0021`、`0024`、`0026`–`0030`、`0032`、`0035`、`0038`、`0041`、`0043`、`0044`、`0045`、`0046`、`0048`、`0049`；`CONTEXT-MAP.md` 与 6 个 `CONTEXT.md`；`docs/agents/*.md`；`docs/25`、`docs/console-testing-baseline.md`、`docs/32`；`deploy/README.md`、`deploy/compose/README(.md/-en)`、`deploy/nacos/README.md`、`deploy/helm/README.md`、`deploy/systemd/README.md`、`examples/README.md`、`saas-forge-sdk/README(.md/-en)`、各契约 README；`docs/local-verification.md` 的流程主体（仅 A10 的点状措辞）。

**实测通过、可作为权威基准**
`bash scripts/validate-nacos-config.sh`（四环境全绿）、`bash scripts/validate-nacos-production-contract.sh`（exit 0）、`python3 scripts/validate-compose-layout.py`（"8 个独立应用、6 个验收场景、项目/网络/卷隔离"）、`pnpm --dir consoles run validate:i18n`。两次大改名（`refactor(namespace)!`、`build(maven)`）的旧命名残留（`com.saasforge`、`services/`、`sdk/`、`contracts/` 路径）在 `docs/` 之外已清零。

**未验证 / 待确认**
- 未运行任何构建或测试（只读审计），文档中的计数类断言（如"104 项边界测试"）未逐条复核。
- `docs/03:14` 的 Kafka 生产参数与 `docs/03:38-39` 的 p95/p99/99.9% SLO：仓库内无压测/SLO/告警配置可核对，未判为矛盾。
- `docs/08:102` 的 Job 模型是否有意作为预留规范，需产品确认。
- 见第六节全部 8 项决策点。
- 四域静态资源专项（`docs/local-static-remote-development.md`）的 7px/11px 偏移与 checksums 未逐条复核。

---

*本报告由 11 个并行只读审计切片 + 机械扫描汇总；每条结论均有 `文件:行号` 或命令输出支撑，其中严重度最高的一批由本人第一手复核（含 `validate-nacos-config.sh`、`validate-compose-layout.py`、`validate:i18n`、`gh issue view 199` 等实测）。*
