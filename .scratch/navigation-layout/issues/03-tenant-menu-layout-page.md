# 03: 布局编辑组件与租户端菜单管理页

**What to build:** 按 [spec](../spec.md) 第 2、7 节，在 `@crane199709/ui` 实现可拖拽的布局编辑组件，并在租户端新增 `/menu-layout` 页面。

**Blocked by:** 01, 02

**Status:** resolved

- [x] 写页面简报并经用户确认（[前端设计规范](../../../docs/agents/frontend-design.md)）
- [x] 编辑组件：拖拽排序与跨分组移动，限制三层、页面不接收子节点；新建、重命名、删除自定义分组，删除时子节点移到原位置；清单分组不能改名或删除
- [x] 编辑组件保存时保留目录外的引用：留在原父节点末尾，父节点不在则放顶层末尾
- [x] 租户端页面：读取 `/navigation-layout`，目录为 Shell Page（首页、通知中心除外）与 `menus`；「保存」整棵提交，「恢复默认」二次确认；保存后刷新当前用户的权限与导航
- [x] 路由与导航按 `iam:menu:manage` 显示，页面键为 `menu-layout`
- [x] 组件测试覆盖 spec 第 8 节编辑组件与租户端管理页各项，文案走 i18n
- [x] 设计走查：新增视觉用例并逐张查看截图

## Comments

### 2026-11-10 页面简报（已经用户确认）

- 使用者：持 `iam:menu:manage` 的租户管理员（Owner、Tenant Admin）。主任务：调整本租户侧边导航的顺序与分组。
- 主操作：「保存」。低频操作：新建分组、重命名/删除自定义分组、恢复默认。
- 原型：表单/设置。`PageHeader`（只有标题）→ 工具条（左：「新建分组」；右：「恢复默认」plain +「保存」主按钮）→ 可拖拽 `el-tree`，内容宽度不超过 `720px`。
- 树行只显示图标与名称，不露页面键和菜单编码。分组带文件夹图标；自定义分组行内有「重命名」「删除」link 按钮，清单分组没有。
- 新建、重命名用小弹窗填写名称（1–32 字），新分组加在顶层末尾。删除分组可通过不保存撤销，不弹确认，子节点移到分组原来的位置。
- 「恢复默认」用确认弹窗说明后果：全体成员的导航恢复默认顺序。
- 读取失败显示错误与「重试」；保存失败用消息提示。

### 2026-11-10 实现说明

- `@crane199709/ui` 导出 `NavigationLayoutEditor`：props `catalog: NavigationEntry[]`（`children` 表示清单分组，不需要 `path`）、`layout: NavigationLayout | null`、`busy`；事件 `save(layout)` 与 `reset`。工具条（新建分组 / 恢复默认 / 保存）在组件内，「恢复默认」的确认弹窗与接口调用由页面负责，便于两端写各自的影响说明。`layout` 变化时丢弃未保存的修改。
- 纯逻辑在 `navigation-editor.ts`（目录 + 布局 → 编辑树、拖放限制、转回布局），单测覆盖；jsdom 中无法模拟 el-tree 拖拽，组件测试经 `allowDrop` 验证拖放限制。
- 目录外的引用放回原父节点会超过三层时放到顶层末尾，避免保存时被后端按层数拒绝。
- 租户端 Shell Page 列表移到 `navigation/shell-pages.ts`，导航与管理页共用；菜单管理页在默认树中排在清单菜单之后。
- 保存失败用 `ElMessage` 提示（仓库中首次使用），成功时提示「已保存」。
- 视觉回归新增 `menu-layout-*` 5 张截图。现有租户端截图的侧边栏末尾多出「菜单管理」，差异低于 `maxDiffPixelRatio`，基线未重新生成。

### 2026-11-10 代码评审修复

- 后端：租户布局的读写移入 `tenant` 功能包（`TenantMapper` + `TenantService`），`navigation` 只经 `TenantService` 访问；供 access 下发、不校验权限的读取方法改名为 `tenantLayoutForAccess` / `platformLayoutForAccess`；平台角色字段注释移回 `PlatformAccessResponse`。
- 前端：`arrangeNavigation` 与 `editorTree` 共用 `placeNavigation`，三层与分组名称 32 字的限制共用 `navigationLimits`；两端管理页的 `ErrorTip` 都取自 `@crane199709/ui`。
- 新节点放入祖先分组会超过三层时放到顶层末尾（规格第 3 节）。
- 目录外分组里显示出来、未被拖动过的节点，保存时按原顺序写回该分组，分组放在原位置；拖动过的节点保存在拖动后的位置（规格第 7 节）。
- 去掉保存与恢复默认的成功提示及对应文案，只保留失败提示；保存后仍刷新导航。
- 视觉基线全部重新生成：租户端与平台端各页侧边栏末尾多出「菜单管理」，`platform-navigation-desktop` 另有分组展开后行距统一的差异，其余无变化。
- 规格补充：空白的 `page`/`menu` 引用与全空白的分组名称被拒绝。
