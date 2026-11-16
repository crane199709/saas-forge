# 平台控制台 AI 模型管理

状态：设计已确认，待实现。接口、加密与迁移在 ai-platform 仓库，后端规格见 `sub/ai-platform/.scratch/platform-model-management/spec.md`（本地路径 `/Users/liuhuan/workspace/coding/sub/ai-platform/.scratch/platform-model-management/spec.md`）。

## 问题

平台控制台没有模型管理页面：接入一个模型要打开浏览器控制台调用 `/ai-platform/models`，而且新增前必须先改部署环境变量、重启 AI 服务。平台运营无法自助维护目录。

## 范围

平台运营人员（Operator）在控制台完成四项操作：新增模型、编辑模型、启用、禁用。

不做：删除（目录项被历史会话与用量引用，用禁用替代）、供应商连通性测试（保存仍不验证可用性）、批量导入、租户侧模型页（已有“模型选择”页）。

## 已确认的决策

| 决策 | 结论 | 为什么 |
|---|---|---|
| 承载工程 | `frontend/apps/platform-console` 新增本地页 | 平台 Realm 会话、`usePlatformClient()`、`platform:ops:manage` 都已就绪；控制台明确不承载 Sub-App，不能把租户模型页挂到平台身份下 |
| 密钥方式 | 直接填 API Key，AI 服务加密存储 | 每接一个模型改一次部署 Secret 并重启，目录维护退化成运维工单；旧 `secretRef` 模型保留兼容 |
| 权限门 | 页面与按钮都用 `platform:ops:manage` | AI 服务所有 `/platform/models` 接口只认它；用 `platform:ops:read` 做页面门会让只读账号进去吃 403 |
| 编辑契约 | 元数据全量替换，凭据缺省=保持 | 表单整表提交；缺省必须表达“不动密钥”，否则每次编辑都要重填 |
| 操作位置 | 新增/编辑用抽屉，启停在列表行 | 平台控制台既有范式；启停是高频小操作 |
| 列表 | 全量列表 + 名称搜索 + 状态筛选 | 目录接口返回全集、条目少；不引入服务端分页 |
| 菜单 | 「AI 模型」，`/ai-models`，排在「应用注册表」之后 | 与 AI 服务接入相邻；任何新增菜单项都会让全部平台视觉基线失效，位置一次定好 |
| 旧模型 | 提示“部署引用”，填 Key 即一次性切换 | 避免同一种凭据长期两套 UX，同时允许把已有模型迁到新方式 |

## 页面简报

- 使用者：持有 `platform:ops:manage` 的平台运营。
- 主任务：把模型接入平台目录，并维护其可用状态。
- 首屏：列表即主内容；主操作「新增模型」在工具条右侧。
- 布局（按 `docs/standards/sub-app.md` 列表页原型）：`PageHeader` → `PageToolbar`（左搜索 + 状态筛选，右主操作）→ `el-table` → 空态/分页区（本页无分页）。

列表列：

| 列 | 内容 |
|---|---|
| 名称 | 目录显示名，`min-width` + `show-overflow-tooltip` |
| 提供方 | `DEEPSEEK` 显示「DeepSeek」，`OPENAI_COMPATIBLE` 显示「OpenAI 兼容服务」 |
| 模型标识 | `modelId`，等宽展示 |
| 能力 | `el-tag size="small"` 逐项 |
| 上下文窗口 | 数字右对齐 |
| 凭据 | 「直接密钥」/「部署引用」/「—」 |
| 状态 | `el-switch`，禁用需二次确认 |
| 操作 | `fixed="right"`，仅「编辑」 |

## 交互

### 新增 / 编辑抽屉

用 `el-drawer :size="overlaySize.drawerLg"`，新增与编辑共用；顶部标签表单，字段顺序：

1. 名称（必填，≤120）
2. 提供方（下拉：DeepSeek / OpenAI 兼容服务）
3. 模型标识（必填，`[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}`）
4. 接入地址（必填，HTTPS，禁止 userinfo、query、fragment）
5. 能力（多选，至少一项：CHAT / TOOLS / VISION / REASONING）
6. 上下文窗口（数字，≥1）
7. API Key（`type="password"`，`autocomplete="off"`）

API Key 的行为：

- 新增：必填。
- 编辑：留空表示保持现有密钥，填写表示替换；不提供“清除密钥”。
- 编辑引用型模型：字段上方显示提示「当前密钥由部署环境注入，填写新密钥将改为平台加密保存，原引用不再使用。」
- 编辑时任何情况下都不回显已保存的密钥，接口也不返回。

新增时 `status` 固定提交 `ENABLED`，状态不在表单里改；表单校验沿用控制台既有做法（`required`、`maxlength`、提交前整理 payload），不引入 `el-form :rules`。

### 启用 / 禁用

行内开关，切换前弹 `el-dialog` 确认：

- 禁用文案：禁用后所有租户的后续模型调用都会失效，已经打开的输出流可能继续。
- 启用文案：启用后租户可重新选择并调用该模型。

取消不发请求；失败时恢复开关状态并展示错误。

### 加载、空态与错误

- 列表空：`EmptyState`，文案「还没有模型。新增后可让租户选择并调用。」
- 无筛选结果：文案「没有符合条件的模型。」
- 加载失败：`ErrorAlert`，用服务端 `userTip`，缺省走 `errorCode.*`，附带 `traceId`。
- 保存失败：`409 model.duplicate` 等错误显示在抽屉内，不关闭抽屉、不伪造成功。

## 接口

前端只调 `client.api('/ai-platform/models')`，`/api` 前缀由 SDK 添加，网关按 `platform` Realm 校验平台 Token。字段与错误码以 ai-platform 后端规格为准；本页用到的写载荷：

```json
{
  "name": "DeepSeek Chat",
  "provider": "DEEPSEEK",
  "modelId": "deepseek-chat",
  "baseUrl": "https://api.deepseek.com",
  "apiKey": "sk-...",
  "capabilities": ["CHAT", "TOOLS"],
  "status": "ENABLED",
  "contextWindow": 65536
}
```

- 编辑时未填写 API Key：请求体不出现 `apiKey` 与 `secretRef`，由服务端保留原凭据。
- 列表响应的 `credentialKind` 决定「凭据」列；缺失按「—」处理。

## 文案与 i18n

新增文案进 `src/locales/zh-CN.json`，按 `aiModel.*` 分组：菜单标题、页面标题、列表列头、表单标签、占位符、按钮、禁用/启用确认、空态、错误提示。不在组件里硬编码中文。

## 验收

- 菜单「AI 模型」在有 `platform:ops:manage` 时出现，无该权限时不出现且直接访问 `/ai-models` 进 403。
- 列表加载、空态、失败态分别可见，失败态带 `traceId`；`credentialKind` 的三种状态（`API_KEY`、`REFERENCE`、空）渲染正确。
- 新增成功：`POST` 请求体含 `apiKey`、不含 `secretRef`，201 后关闭抽屉并刷新列表；409 重复时留在抽屉并显示错误。
- 编辑成功：未填 Key 时请求体不含 `apiKey`/`secretRef`；填了 Key 时含 `apiKey`、不含 `secretRef`。
- 引用型模型编辑时提示可见；提交后「凭据」列变为「直接密钥」（刷新后）。
- 禁用：确认弹窗文案正确；取消不发请求；确认后以完整元数据 + `status=DISABLED` 提交 `PUT`，列表行状态更新。
- 组件测试覆盖以上交互；1 条 Playwright e2e 覆盖“新增 → 列表出现 → 禁用确认”；`pnpm lint:style` 与 `pnpm test:visual` 通过。
- 视觉基线：列表、新增抽屉、编辑引用型、禁用确认、空态、错误态，各 1440×900 与 1024×768。

## 交付拆分

1. ai-platform：凭据存储与接口（见后端工单 01），部署与契约文档（工单 02）。
2. saas-forge：页面与菜单（工单 01），视觉基线与 e2e（工单 02）。

后端先合入，前端可按本文契约并行开发；两个仓库各一个 PR。

## 风险与约束

- 新增菜单项与页面会让**全部平台视觉基线**重刷（侧边栏变化），需要一次性 `--update-snapshots=all` 并逐张走查。
- 前端验收强依赖后端契约；`apiKey` 字段与 `credentialKind` 未合入时，页面只能对着 mock 开发，不能声称完成。
- 直接密钥让数据库备份的敏感范围扩大，属于 ADR 0005 的已知取舍，页面只负责“不回显、不落日志”。

## 不在范围

- 删除模型、主密钥轮换、连通性测试。
- 租户侧模型选择页的调整。
- 平台控制台其它页面的重构。
