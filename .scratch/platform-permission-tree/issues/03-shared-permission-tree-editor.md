# 03: 授权树组件提取到 `@crane199709/ui`

**What to build:** 按 [spec](../spec.md) 第 4 节，把 tenant-app 的授权树组件和勾选规则提取到 `@crane199709/ui`，并增加 Plan 需要的两项能力。tenant-app 改为引用后，行为不变。

**Blocked by:** 无

**Status:** ready-for-agent

- [ ] `PermissionTreeEditor` 与 `permission-selection` 迁入 `@crane199709/ui`，按该包现有的组件写法与 local scope i18n 实现，并从包入口导出组件及其数据类型
- [ ] tenant-app 改为引用共用组件，删除原文件，`data-testid` 不变
- [ ] 增加「页面权限作为子项」的显示方式，默认保持租户侧现状；开启后根节点只负责全选和半选，页面权限显示为第一个子项
- [ ] 支持调用方传入额外的权限分组，显示在「功能权限」区之后；组件不感知 Plan
- [ ] 两种显示方式下，勾选规则与租户侧一致：勾选页面授予页面权限及全部操作，勾选操作自动勾上页面权限，取消页面权限连带取消操作，共用码同步，不挂权限码的页面显示「始终可见」且不可勾选
- [ ] `@crane199709/ui` 测试覆盖两种显示方式、勾选联动与额外分组
- [ ] tenant-app 角色树现有测试全部通过

## Comments

