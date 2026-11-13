# 字典变更历史验收

日期：2026-11-13。验收分支：`codex/dictionary-history-03`，基于集成 `bb4ae36a`。

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

IAM 模块全量与最终双轴审查由集成负责人在合并本票后执行；此处不预报其结果。

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
