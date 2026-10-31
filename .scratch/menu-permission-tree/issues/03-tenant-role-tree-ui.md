# 03: 租户端角色页树形授权

**What to build:** 按 [spec](../spec.md) 第 4 节把租户端角色页的新建与编辑授权改为「菜单 → 操作」树加「功能权限」列表，提交仍为 Permission Code 列表。

**Blocked by:** 02

**Status:** resolved

- [x] 读取 `GET /roles/permission-tree` 渲染授权树与「功能权限」区
- [x] 勾选叶子 = 授予其权限码；勾选分组 = 勾选全部子孙；不挂权限码的叶子显示「始终可见」且不可勾选
- [x] 勾选 action 自动勾上所在页面；取消页面时其下 actions 一并取消
- [x] 共用权限码的节点同步勾选，并提示与哪些菜单共用
- [x] 每个 action 与功能权限区的权限码可单独勾选或取消，例如只授予查看与创建、不授予删除
- [x] 编辑已有角色时按其权限码回显勾选状态
- [x] 提交时去重为权限码列表，沿用 `PUT /roles/{id}/permissions` 与新建接口
- [x] 文案走 i18n；单元测试覆盖勾选联动规则

## Comments


## Answer

- 租户角色页改读 `GET /roles/permission-tree`；新建与编辑共用菜单与操作树，功能权限继续按服务、分组展示。
- 以 Permission Code 集合作为选择状态：分组全选及半选、无权限叶子的「始终可见」、共用权限提示与同步、操作自动补齐页面、取消页面连带取消操作；同一码兼任页面权限与另一页操作时传播到全部引用位置，避免漏选或残留。编辑回显不擅自补齐历史授权，提交仍沿用原接口并去重。
- 所有新增文案使用 i18n；未修改平台角色或后端授权校验。
- TDD：先运行新增角色树组件测试确认失败，再实现并验证通过。组件测试覆盖编辑回显、共享提示、单独取消操作、取消页面联动、新建分组授权、跨页面传播、功能权限分组与接口提交；页面测试验证树接口加载。
- 验证：`mise exec node@24.14.1 -- corepack pnpm --filter @forge/tenant-app test --maxWorkers=1`，21 个文件 / 119 项通过；`pnpm --filter @forge/tenant-app build`（同 Node 环境）包含 vue-tsc 类型检查与 Vite 构建，通过，仅既有大分块警告；本次文件 Prettier 与 `git diff --check` 通过。
- 未运行真实后端浏览器 E2E；本 ticket 通过真实 Vue/Element Plus 组件与 HTTP 边界替身验证，真实业务服务清单联调由后续验收覆盖。
