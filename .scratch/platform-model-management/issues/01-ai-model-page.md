# 01: 平台控制台 AI 模型页面

**What to build:** 按 [spec](../spec.md) 在平台控制台新增「AI 模型」页面：全量列表 + 名称搜索 + 状态筛选，新增/编辑抽屉，行内启用/禁用，调用 `/ai-platform/models`。

**Blocked by:** 前端实现已按目标契约完成；真实联调仍依赖 ai-platform `.scratch/platform-model-management/issues/01-credential-storage.md`（`apiKey` 与 `credentialKind` 契约），本工单的组件测试通过不代表该依赖已验收。

**Status:** resolved

- [x] 在 `src/router.ts` 的 `consolePages` 增加 `/ai-models`，标题「AI 模型」，图标取白名单内名字，`permission: permissions.opsManage`，排在应用注册表之后
- [x] 新增 `src/aimodel/` 目录：`AiModelPage.vue`（薄壳，注入 api 与权限）、`AiModelPanel.vue`、`ai-model-api.ts`、`ai-model-api` 的 `list/create/update` 封装
- [x] 列表：名称、提供方、模型标识、能力、上下文窗口、凭据、状态、操作；搜索与状态筛选在客户端过滤
- [x] 新增/编辑共用 `drawerLg` 抽屉，字段与校验按 spec；编辑留空不提交 `apiKey`/`secretRef`
- [x] 引用型模型编辑时显示“部署引用”提示；填写 Key 提交 `apiKey` 且不传 `secretRef`
- [x] 行内 `el-switch` + 禁用二次确认，取消不发请求，失败恢复开关
- [x] 空态、无结果、加载失败、保存失败（含 409 `model.duplicate`）分别可见，失败态带 `traceId`
- [x] 文案全部进 `src/locales/zh-CN.json` 的 `aiModel.*`，组件不硬编码中文
- [x] 组件测试覆盖列表三态、新增提交体、编辑留空、引用型切换、禁用确认、无 `manage` 不可见
- [x] `pnpm --filter @forge/platform-console typecheck` 与 `test` 通过

## Comments


## Answer

2026-11-16：工单 01 的前端实现与组件/HTTP 边界验证完成；后端真实联调和工单 02 的视觉、e2e 验收尚未执行。

- `/ai-models` 使用现有 `platform:ops:manage` 权限门，在默认菜单中紧随应用注册表；无权限访问进入 403。`AiModelPage` 仅注入平台客户端及权限，`AiModelPanel` 承担页面交互。
- 全量目录在客户端按名称、状态筛选，展示提供方、模型标识、能力、上下文窗口、凭据来源、状态与编辑入口。加载、空目录、无结果、错误分别呈现，失败带追踪号并可重试。
- 新增与编辑共用 `drawerLg` 抽屉；字段按规格校验。新增提交 `apiKey` 与 `ENABLED`，编辑留空时省略 `apiKey`/`secretRef`，填写新 Key 时只发送 `apiKey`。引用型模型有明确切换提示；保存失败保留抽屉，成功关闭并刷新目录；关闭或保存后清除输入的 Key，不回显已保存凭据。
- 启用、禁用都先确认，取消不发请求。确认提交完整元数据及目标状态，不触碰凭据；开关由目录状态控制，失败保持原状态并展示错误，支持重试。
- 文案集中在 `aiModel.*`；接口错误先用服务端 `userTip`，缺省匹配 `errorCode.*`，再回退通用错误。新增了重复模型等本地错误码文案。
- TDD 按功能行为逐切片推进：权限入口、列表、筛选、加载错误重试、新增、重复模型、编辑保持凭据、引用切换、字段校验、启停确认均先运行失败用例再实现；测试只经真实 Vue/Element Plus 交互及 mock fetch 的 HTTP 边界。模型组件 22 个用例覆盖关键分支，路由测试覆盖入口权限；同步了两个既有菜单目录断言。
- 验证通过：`pnpm --filter @forge/platform-console test`（20 文件、128 用例）、`pnpm --filter @forge/platform-console typecheck`、`pnpm lint:style`、本次 9 个前端文件的 Prettier 检查与 `git diff --check`。首轮全量检查暴露的菜单目录断言、Element Plus 行类型已修复并重新验证。
- 验收边界：这些请求由 mock fetch 应答，只验证前端与目标契约；尚未验证真实 ai-platform 的 `apiKey`/`credentialKind`、加密存储或供应商调用。未修改后端，也未运行或改动工单 02 的视觉/e2e 用例与基线。
