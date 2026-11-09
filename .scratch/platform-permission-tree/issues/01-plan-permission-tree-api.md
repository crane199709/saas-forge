# 01: Plan 授权树接口

**What to build:** 按 [spec](../spec.md) 第 2、3 节，在 iam 与 platform 增加 Feature Pack 授权树的读取接口。本工单只增加接口，`GET /platform/plans/permissions` 的删除放在工单 03，保证前端在切换前仍可用。

**Blocked by:** 无

**Status:** ready-for-agent

- [ ] iam 新增 `GET /internal/permission-tree`，加入 `manifestSecurity` 的 `securityMatcher`，由网关拒绝外部访问
- [ ] 权限范围取 `planScopedPermissions()`，不含 iam 自身的管理权限码
- [ ] 菜单不按 Plan 或 Sub-App 裁剪；权限码不在范围内的节点连同子树隐藏，范围外的 action 去掉，不挂权限码的叶子保留
- [ ] 菜单节点增加 `app`，分组节点为 null；`sharedWith` 按整棵树计算
- [ ] `others` 为范围内、未出现在菜单树上的权限码，按服务、分组、编码排序
- [ ] 复用裁剪与树生成逻辑时遵守跨功能包只经由 `service` 访问的规则；`/roles/permission-tree` 的现有行为不变
- [ ] platform 新增 `GET /platform/plans/permission-tree`，权限与现有 Plan 接口相同，透传 iam 结果；iam 不可用时返回 `plan.catalogUnavailable`
- [ ] iam 集成测试：不按 Plan 裁剪、不含 iam 管理权限码、`others` 正确、叶子带 `app`、缺少上报令牌时被拒绝
- [ ] platform 集成测试：透传、权限校验、iam 不可用

## Comments

