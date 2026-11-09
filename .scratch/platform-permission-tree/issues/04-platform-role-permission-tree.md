# 04: 平台角色改为按控制台页面的授权树

**What to build:** 按 [spec](../spec.md) 第 6 节，让 Operator 按控制台页面为平台角色勾选权限码。树完全由前端生成，后端不改。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] `consolePages` 增加 `actions`：`/tenants` 带 `tenantManage`，`/task-runs` 与 `/dead-events` 带 `opsManage`，其余页面没有
- [ ] 实现由 `consolePages` 与 `/platform/roles/permissions` 生成树的纯函数：每个页面一个根，标题取页面 i18n 文案，权限名称取接口结果
- [ ] 不在接口结果中的页面隐藏，不在其中的 action 去掉；`sharedWith` 由前端计算
- [ ] 未挂到页面上的平台权限码按服务、分组进入「功能权限」区
- [ ] `PlatformRolePanel` 的新建与编辑使用共用组件的「页面权限作为子项」方式，提交接口不变
- [ ] 超级管理员的只读行为不变
- [ ] 组件测试覆盖 10 个根、运维权限同步勾选、提交的权限码正确、超级管理员只读，文案走 i18n
- [ ] 在 menu-permission-tree 规格第 6 节关闭「平台角色授权界面是否采用同样的树」，指向本规格

## Comments

