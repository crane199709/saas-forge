# 02: iam 授权树接口

**What to build:** 按 [spec](../spec.md) 第 3 节新增 `GET /roles/permission-tree`，返回按 Plan 裁剪的菜单授权树、共用关系 `sharedWith` 与未进树的权限码 `others`。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 接口要求 `iam:role:manage`，返回 `ApiResponse<T>`，响应模型用 `record`
- [ ] 菜单按 Plan 裁剪，规则与 `AccessService` 一致：权限码不可授予的节点连同子树隐藏、Sub-App 不在 Plan 中的叶子隐藏、空分组隐藏；不按当前用户授权裁剪
- [ ] 不可授予的 action 去掉；不挂权限码的叶子保留且 `permission` 为 null
- [ ] `sharedWith` 列出同一权限码在可见树中出现的其他菜单编码
- [ ] `others` 为可授予但未出现在可见树中的权限码，排序与 `grantablePermissions` 相同
- [ ] 未登记 Plan 的租户不受 Plan 裁剪
- [ ] 集成测试覆盖：共用权限码、Plan 外权限码与 Sub-App、iam 自身权限码落入 `others`、无权限访问返回 403

## Comments
