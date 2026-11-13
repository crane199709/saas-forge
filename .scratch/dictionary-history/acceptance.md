# 字典变更历史验收

日期：2026-11-13。交付分支：`codex/dictionary-history`，最终代码集成点 `cb65a169`；票据 03 验收基于 `bb4ae36a`。

## 已确认的结果

- 有效新增、改名、排序及启停追加租户覆盖快照；无变化保存不追加版本。版本按租户、服务、字典隔离。
- 历史列表倒序展示，单版本保留完整覆盖快照；任意两版支持 added / removed / changed。正向新增覆盖可通过反向比较显示为移除覆盖，无需引入删除或回滚。
- 当前生效值、当前服务声明是查询时上下文。重复启动登记、Manifest 升级不生成历史；旧快照中的未覆盖字段仍为空，不被升级后的服务默认值改写。
- `dictionary.itemCreate`、`dictionary.itemUpdate`、`dictionary.itemDisable`、`dictionary.itemEnable` 审计事件仍独立写入 Outbox；同一测试同时验证四次字典有效变更产生四条历史，参数修改没有混入字典历史。
- 独立历史权限与跨租户隔离沿用票据 02 的 HTTP / Vue 回归；本票相关两类回归再次覆盖已有权限拒绝和 cross-tenant 404。

## 验证命令与证据

| 验证 | 实际命令 | 结果 |
| --- | --- | --- |
| 新增登记与审计验收 | `cd backend && ./mvnw -o -pl forge-iam spotless:apply test '-Dtest=ReferenceDataTest#repeatedStartupReportsAndManifestUpgradesDoNotAppendHistoryOrRewriteSnapshots+changesAdvanceTheTenantsReferenceDataVersionAndAreAudited'` | 2 PASS |
| 相关后端目标类 | `cd backend && ./mvnw -o -pl forge-iam test -Dtest=ReferenceDataTest,DictionaryHistoryTest` | 24 PASS：ReferenceData 17、History 7 |
| 真实服务链路 | `FORGE_E2E_OFFLINE=1 deploy/e2e/run.sh --grep '字典有效修改产生历史'` | 1 PASS（用例 10.5 秒，runner 13.8 秒） |
| 工具 TypeScript | `cd frontend && pnpm typecheck:tools` | PASS |
| 修改的前端文件格式 | `cd frontend && pnpm exec prettier --check e2e/main-flow/main-flow.spec.ts` | PASS |
| Java 格式 | `cd backend && ./mvnw -o -pl forge-iam spotless:check` | PASS |
| 变更空白检查 | `git diff --check` | PASS |

本票后端回归是对已实现能力补验收，首跑通过，没有为制造失败修改生产代码。真实 UI 用例前三轮暴露测试定位器与身份断言的问题：Element Plus 的输入 `data-testid` 落在原生 input；两个版本控件的弹层在过渡期均含同名 option。第三轮还发现 HTML 初始标题为 `SaaS Forge`，但 `main.ts` 加载中文文案后最终标题为 `SaaS Forge 租户端`，原断言存在初始化竞态。已分别改为直接操作输入、通过目标 combobox 的 `aria-controls` 限定 listbox、断言源码定义的最终标题，再重跑；未改产品代码。

集成负责人已在 `bb4ae36a` 执行并确认：`pnpm test` 全仓 420 PASS（UI 72、sub-app-kit 7、platform-console 100、tenant-app 241），`pnpm typecheck` 全 workspace 与 tools PASS，`./mvnw -o -pl forge-iam -am install -DskipTests` PASS。票据 02 已执行字典、历史、导航及路由 35 项组件回归、1440/1024 的空列表／版本列表／详情／diff 共 8 项视觉回归，基线已随票据 02 提交。本票未修改页面，未变更视觉基线。

IAM 模块全量与最终双轴审查均已完成，详见文末「最终集成验证与审查」。

## 真实页面 QA

The flow under test is: `/` 实际密码登录 → `/dictionaries` → 选择客户来源 → 修改内置项 → 历史 v1 → 新增自定义项与再次改名／排序 → 历史 v1–v3 → v1→v3 与 v3→v1 比较 → 保存原值后仍为三版。

环境：macOS，仓库 Playwright 1.64.1 Chromium；Browser plugin not available，使用仓库现有 Playwright。页面 URL `http://127.0.0.1:15174/dictionaries`，1440×1000 与 1024×900。真实 IAM、Gateway、demo、platform 与前端均由现有 `deploy/e2e/run.sh` 启动；未 mock 网络或注入登录会话。脚本新建隔离 Compose PostgreSQL / Redis，种子数据仅写本次容器，退出仅清理本次项目和进程，未连接开发数据库或操作其他容器。

| 检查 | 结论与证据 |
| --- | --- |
| 页面身份 | PASS：URL `/dictionaries`、title `SaaS Forge 租户端`、字典标题与 e2e-admin 会话 |
| 非空 | PASS：真实字典、版本列表、diff 和详情均有业务内容 |
| 无框架错误遮罩 | PASS：DOM 断言与截图走查 |
| 浏览器日志 | PASS：pageerror、console error / warning 为空 |
| 交互 | PASS：v1–v3、任意双版本正反向 diff、旧快照／当前值对照、no-op 不增版 |
| 截图 | PASS：下列两张实际 UI 图片已用 `view_image` 逐张查看 |

截图：`/tmp/dictionary-history-qa/desktop-forward-detail.png`、`/tmp/dictionary-history-qa/laptop-reverse-detail.png`。桌面上版本列表、比较控件和差异按区块排列；1024 下字典选择区移到上方，表头／操作不折行，长摘要和变更人截断并可查看提示，正文与当前上下文无裁切、重叠或遮罩。

日志：`/tmp/dictionary-history-03-http.log`、`/tmp/dictionary-history-03-backend.log`、`/tmp/dictionary-history-03-e2e.log`（首轮定位器失败）、`/tmp/dictionary-history-03-e2e-rerun.log`（第二轮定位器歧义）、`/tmp/dictionary-history-03-e2e-final.log`（第三轮最终标题竞态）、`/tmp/dictionary-history-03-e2e-passed.log`（最终运行）。

## 验证边界

- 服务重启的无噪声行为在其真实重复上报 HTTP 边界验证；没有为此额外杀进程再启动。Manifest 升级通过真实登记 HTTP 与历史 HTTP 验证。
- 本次真实端到端只运行新增的一条验收链路；没有重复完整平台主流程套件。浏览器仅 Chromium，未执行手机视口、Firefox 或 WebKit。
- 审计按仓库既有测试接缝验证持久化 Outbox 的 AuditEvent；本次 E2E 环境没有运行 MQ 审计消费链路。
- 不包含回滚、删除或重置租户覆盖、Parameter 历史、Manifest 演进历史。

## 实现指针

- [01：历史持久化与写入](issues/01-history-table.md)：`38d1a0fb` / `00fb3f35`。
- [02：查询、权限与页面](issues/02-api-and-ui.md)：`28eb9957`，集成 `bb4ae36a`。
- [03：验收](issues/03-acceptance.md)：HTTP 补验收在 `ReferenceDataTest`；真实 UI 在 `frontend/e2e/main-flow/main-flow.spec.ts`。


## Standards 审查修复（2026-11-13）

修复分支 `codex/dictionary-history-review-fixes` 基于集成 `e4d19bea`，范围仅为本次审查确认的三项：

- 历史列表与详情保留 `changedBy`，附加查询时的 `changedByName`。已有登录名查询会回落邮箱／手机号，因此在身份功能包的 `IdentityService` 与 mapper 增加仅取展示名、空白时取账号的窄查询，参考数据功能只经其 service 调用；列表在单次请求内按身份去重，不增加接口权限、通用解析框架或跨请求缓存。前端展示姓名，无法解析时显示“姓名未知”，原标识仅在明确标注“身份 ID”的提示中；标准列操作人为 null 时显示“系统”。
- Controller 直接返回的三个 record 直接重命名为 `DictionaryHistoryMetadataResponse`、`DictionaryHistoryDetailResponse`、`DictionaryHistoryDiffResponse`，同步引用，不增加镜像 DTO；原 JSON 字段语义不变。
- 历史覆盖快照和当前生效值的两列状态改为小号 `el-tag`：启用 success、停用 info、已下线 warning。沿用已有状态文案和页面布局；diff 内容不变。

### 修复验证

姓名 HTTP 接缝先因缺少 `changedByName` 失败；同一测试改由仅有历史读取权限的审计员查询列表和详情，修复后通过。姓名组件接缝先因显示裸 UUID 失败；修复后同时验证姓名、系统、未知姓名回落和有说明的身份提示。状态组件接缝先因没有标签失败，修复后验证快照及当前值三种状态标签。纯响应重命名没有新增重复测试。

| 验证 | 实际命令 | 结果 |
| --- | --- | --- |
| 后端相关目标类 | `cd backend && ./mvnw -o -pl forge-iam clean spotless:apply test -Dtest=ReferenceDataTest,DictionaryHistoryTest,ArchitectureTest` | 25 PASS（17 + 7 + 1） |
| 字典与历史组件 | `cd frontend && pnpm --filter @forge/tenant-app test src/referencedata/DictionaryHistory.spec.ts src/referencedata/DictionaryPage.spec.ts` | 14 PASS |
| 页面与工具类型 | `cd frontend && pnpm --filter @forge/tenant-app typecheck && pnpm typecheck:tools` | PASS |
| 样式 | `cd frontend && pnpm lint:style` | PASS |
| 格式 | 修改文件 `prettier --check`、`cd backend && ./mvnw -o -pl forge-iam spotless:check`、`git diff --check` | PASS |
| 视觉新基线 | 构建三个应用后，`cd frontend && pnpm exec playwright test -c playwright.visual.config.ts dictionary-history.spec.ts --update-snapshots=all` | 8 PASS |
| 视觉正式比对 | `cd frontend && pnpm exec playwright test -c playwright.visual.config.ts dictionary-history.spec.ts` | 8 PASS（7.9 秒） |
| 真实服务链路 | `FORGE_E2E_OFFLINE=1 deploy/e2e/run.sh --grep '字典有效修改产生历史'` | 1 PASS；禁用截图动画后复跑仍 PASS（用例 12.2 秒，runner 15.6 秒） |

前端使用 `pnpm install --offline --frozen-lockfile` 成功安装，锁文件无变更。姓名 red／green、标签 red／green 日志分别为 `/tmp/dictionary-review-http-{red,green}.log`、`/tmp/dictionary-review-ui-{red,green}.log`、`/tmp/dictionary-review-tags-{red,green}.log`；最终目标类、组件、视觉比对与真实链路日志分别为 `/tmp/dictionary-review-backend.log`、`/tmp/dictionary-review-components.log`、`/tmp/dictionary-review-visual-check.log`、`/tmp/dictionary-review-e2e-final.log`。

### 本次页面走查

The flow under test is: 字典页 → 历史版本列表／空态 → 打开历史详情 → 比较两版，确认变更人名称、身份提示及状态标签。

Browser plugin not available，使用仓库 Chromium Playwright。已用 `view_image` 逐张检查 `frontend/e2e/visual/__screenshots__/dictionary-history-{list,empty,detail,diff}-{desktop,laptop}.png` 共 8 张；1440×900 和 1024×768 下名称与状态可读，标签、表头、按钮未折行，页面无重叠、裁切或框架遮罩。列表／详情／diff 六张基线随姓名和标签变更更新，两张空态保持一致。例：[桌面详情](../../frontend/e2e/visual/__screenshots__/dictionary-history-detail-desktop.png)、[笔记本详情](../../frontend/e2e/visual/__screenshots__/dictionary-history-detail-laptop.png)。

真实链路新增断言 `e2e-admin` 姓名可见、UUID 不作为人员名显示、身份提示包含原 ID；已有页面 URL／最终标题、非空、console error／warning、pageerror、交互与 no-op 断言均通过。首轮桌面真实截图捕获到新标签入场动画中间帧，已把两处截图设为 `animations: 'disabled'` 并复跑同一链路通过，不修改产品动画。最终 `/tmp/dictionary-history-qa/desktop-forward-detail.png` 与 `laptop-reverse-detail.png` 已重新用 `view_image` 逐张查看，姓名及状态标签均完整可读。

本轮只重复相关后端目标、组件、8 项视觉与单条真实链路，不重复 IAM 全量或整个前端套件。最终集成双轴复核已确认通过，详见下节。


## 最终集成验证与审查

三张票据和规格均为 `resolved`，实现、验收与审查修复已合入 `codex/dictionary-history`；代码集成点 `cb65a169` 与修复提交 `c0616e98` 的文件树一致。

| 检查 | 验证基准与命令 | 结果 |
| --- | --- | --- |
| IAM 模块全量 | `e4d19bea`，`cd backend && ./mvnw -o -pl forge-iam verify` | 339 PASS，0 失败、0 错误、0 跳过；架构与 Spotless 通过 |
| 前端全仓 | `bb4ae36a`，`cd frontend && pnpm test` | 420 PASS（UI 72、sub-app-kit 7、platform-console 100、tenant-app 241） |
| 全仓类型 | `bb4ae36a`，`cd frontend && pnpm typecheck` | 全 workspace 与 tools PASS |
| 审查修复复验 | `c0616e98`，上节定向命令 | 后端 25、组件 14、视觉 8、真实 E2E 1 均 PASS；相关类型、样式与格式通过 |
| 权限目录 | 票据 02 的四份真实 Manifest 导出、目录比对与脚本测试 | 目录一致，19 项脚本测试 PASS |

IAM 全量前已执行 `./mvnw -o -pl forge-iam -am install -DskipTests` 更新本地 SDK 制品。前端全量首轮因沙箱禁止本地代理端口监听而中止，允许该测试监听临时本地端口后全套通过。全量日志为 `/tmp/dictionary-history-iam-full.log`、`/tmp/dictionary-history-frontend-all.log`、`/tmp/dictionary-history-frontend-types.log`、`/tmp/dictionary-history-final-install.log`。审查修复后仅复验受影响接缝，不重复无关全套。

两个独立审查代理分别执行 `git diff 71ddd5dd...e4d19bea`，并复核 `e4d19bea...c0616e98`：

- **Standards**：初审 3 项（变更人 UUID、响应命名、状态标签）全部修复；最终剩余 **0 项**。
- **Spec**：初审与修复复核均 **0 项**；姓名为查询时上下文，不改变持久化快照、版本或 diff。

最终真实截图还保存在 `/Users/liuhuan/.codex/visualizations/2026/11/13/01a1d394-5beb-7663-8a55-b7b702981157/dictionary-history-e2e.png`。交付仍遵守上文验证边界，未推送或操作开发数据库。
