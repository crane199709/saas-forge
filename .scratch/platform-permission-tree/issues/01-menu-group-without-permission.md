# 01: 菜单分组不挂 Permission Code

**What to build:** 按 [spec](../spec.md) 第 2 节，清单校验拒绝带 `permission` 的分组节点，权限码只挂在叶子菜单上。

**Blocked by:** 无

**Status:** ready-for-agent

- [ ] `ResourceManifest.validate()`（SDK 生成与 iam 接收共用）拒绝带 `permission` 的非叶子菜单，错误信息说明权限码只能挂在页面上
- [ ] SDK 测试：带权限码的分组被拒绝，现有清单测试通过
- [ ] 改写 `RoleManagementTest` 中依赖分组权限码的 `pt.blocked` 用例，保持它对「隐藏」规则的覆盖
- [ ] demo 与 forge-platform 的清单无需改动，确认生成与上报仍通过

## Comments
