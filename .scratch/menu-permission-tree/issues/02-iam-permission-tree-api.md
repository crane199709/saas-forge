# 02: iam 授权树接口

**What to build:** 按 [spec](../spec.md) 第 3 节新增 `GET /roles/permission-tree`，返回按 Plan 裁剪的菜单授权树、共用关系 `sharedWith` 与未进树的权限码 `others`。

**Blocked by:** 01

**Status:** resolved

- [x] 接口要求 `iam:role:manage`，返回 `ApiResponse<T>`，响应模型用 `record`
- [x] 菜单按 Plan 裁剪，规则与 `AccessService` 一致：权限码不可授予的节点连同子树隐藏、Sub-App 不在 Plan 中的叶子隐藏、空分组隐藏；不按当前用户授权裁剪
- [x] 不可授予的 action 去掉；不挂权限码的叶子保留且 `permission` 为 null
- [x] `sharedWith` 列出同一权限码在可见树中出现的其他菜单编码
- [x] `others` 为可授予但未出现在可见树中的权限码，排序与 `grantablePermissions` 相同
- [x] 未登记 Plan 的租户不受 Plan 裁剪
- [x] 集成测试覆盖：共用权限码、Plan 外权限码与 Sub-App、iam 自身权限码落入 `others`、无权限访问返回 403

## Comments

- 2026-10-31 最终审查维护修复：将导航与授权树的权限码、Plan Sub-App、空分组裁剪统一至 `PermissionService.visibleMenus`，调用方分别保留导航去除 actions、授权树仅展示可授予 actions 的投影；授权语义不变。
- 复用已授权 HTTP seam 回归：`ResourceManifestTest` 16 项、`RoleManagementTest` 8 项以及 11 项相关模块 `ArchitectureTest` 全部通过（35 项，零失败/跳过），Testcontainers 隔离 PostgreSQL/Redis；`spotless:check` 与 `git diff --check` 通过。
- 命令：`cd backend && ./mvnw -q -pl forge-iam -am spotless:apply test -Dtest=ResourceManifestTest,RoleManagementTest,ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false`，随后同 reactor 执行 `spotless:check`。本次是审查阶段的行为保持重构，沿用现有边界测试，未增加实现细节测试。

## Answer

新增 `GET /roles/permission-tree`，沿用 `iam:role:manage`，响应为 record 与 `ApiResponse`。按当前租户可授予权限和 Plan 的 Sub-App 裁剪菜单、actions 与空分组；共用关系仅统计可见树，未进树的权限保持原有排序进入 `others`。未登记 Plan 的租户不受裁剪，不按请求成员的业务权限裁剪。

验证（2026-10-31）：

- TDD 首切片先观察接口缺失返回 405，再实现后通过；Plan 切片先观察未裁剪导致 500，再实现后通过。
- `./mvnw -q -pl forge-iam -am spotless:apply test -Dtest=RoleManagementTest,ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false` 通过：角色接口测试 8 项（含新增 2 项）及 11 项相关 reactor 模块架构检查，共 19 项；`spotless:check` 与 `git diff --check` 通过。
- 覆盖共用菜单/action 权限、null 权限页面、iam 管理权限进入 others、403、仅有角色管理权限时仍返回完整树、Plan 外权限/子应用/空分组隐藏、隐藏页面权限回收到 others 与排序。
- 全部数据库测试使用独立 Testcontainers PostgreSQL/Redis；没有连接或修改现有数据库。
