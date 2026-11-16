# 01: 平台控制台 AI 模型页面

**What to build:** 按 [spec](../spec.md) 在平台控制台新增「AI 模型」页面：全量列表 + 名称搜索 + 状态筛选，新增/编辑抽屉，行内启用/禁用，调用 `/ai-platform/models`。

**Blocked by:** ai-platform `.scratch/platform-model-management/issues/01-credential-storage.md`（`apiKey` 与 `credentialKind` 契约）

**Status:** ready-for-agent

- [ ] 在 `src/router.ts` 的 `consolePages` 增加 `/ai-models`，标题「AI 模型」，图标取白名单内名字，`permission: permissions.opsManage`，排在应用注册表之后
- [ ] 新增 `src/aimodel/` 目录：`AiModelPage.vue`（薄壳，注入 api 与权限）、`AiModelPanel.vue`、`ai-model-api.ts`、`ai-model-api` 的 `list/create/update` 封装
- [ ] 列表：名称、提供方、模型标识、能力、上下文窗口、凭据、状态、操作；搜索与状态筛选在客户端过滤
- [ ] 新增/编辑共用 `drawerLg` 抽屉，字段与校验按 spec；编辑留空不提交 `apiKey`/`secretRef`
- [ ] 引用型模型编辑时显示“部署引用”提示；填写 Key 提交 `apiKey` 且不传 `secretRef`
- [ ] 行内 `el-switch` + 禁用二次确认，取消不发请求，失败恢复开关
- [ ] 空态、无结果、加载失败、保存失败（含 409 `model.duplicate`）分别可见，失败态带 `traceId`
- [ ] 文案全部进 `src/locales/zh-CN.json` 的 `aiModel.*`，组件不硬编码中文
- [ ] 组件测试覆盖列表三态、新增提交体、编辑留空、引用型切换、禁用确认、无 `manage` 不可见
- [ ] `pnpm --filter @forge/platform-console typecheck` 与 `test` 通过

## Comments
