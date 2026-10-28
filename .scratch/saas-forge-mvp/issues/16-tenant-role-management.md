# 16: 租户角色管理

**What to build:** Tenant Admin 在 tenant-app 创建、编辑、删除自定义 Role，为 Role 勾选 Permission Code（来自 Resource Manifest），并把 Role 分配给 Membership。变更即时生效：被调整的成员下一次请求就按新权限处理，菜单与按钮随之变化。

**Blocked by:** 07 Token 即时失效与刷新, 12 资源清单与功能权限校验

**Status:** ready-for-agent

- [x] Role 的增删改与权限码授权；预置角色（Owner、Tenant Admin）不可删除
- [x] 权限码按服务与分组展示，失效的权限码不可再授予
- [x] 一个 Membership 可拥有多个 Role，权限取并集
- [x] 角色或授权变更时递增受影响 Membership 的 version
- [x] 业务服务在 Provisioning 时预置的角色模板可见、可编辑
- [x] tenant-app 提供角色列表、权限勾选与成员分配界面
- [x] 测试：撤销某权限后该成员下一次请求得到 403，菜单同步消失


## Comments

2026-10-28 实现：
- 数据：V11 新增纯关联表 `role_permission (role_id, permission_id)`。授权按权限码 id 记录；整体替换授权时只增删 active 的编码，已下线编码上的授权保留但不生效，权限码重新上报恢复后授权随之恢复。预置角色仍不落授权记录。
- iam `role` 功能包：`/roles`（列表含持有人数与有效授权）、`GET /roles/permissions`（可授予的有效权限码，按服务、分组排序）、`POST /roles`（编码小写字母/数字/连字符，创建后不可改，保留编码与已用编码 409 `role.codeTaken`）、`PUT /roles/{id}`（改名）、`PUT /roles/{id}/permissions`（整体替换，失效或不存在的编码 400 `role.invalidPermission`）、`DELETE /roles/{id}`（仍有成员持有 409 `role.inUse`）、`GET /roles/memberships`（分页）与 `PUT /roles/memberships/{id}`（整体替换成员的角色，可多选、权限取并集）。预置角色的改名、授权与删除一律 409 `role.builtin`；Owner 不在分配范围内（400 `role.invalidAssignment`），成员已持有的 Owner 保持不变，转让留给工单 17。写操作在同一租户内以咨询锁串行；授权变化影响的持有者、分配变化的成员都在事务提交后递增 version。
- 管理权限改为按权限码：iam 启动时把自身清单 `forge-iam@1`（`iam:org:manage`、`iam:role:manage`，分组「租户管理」）直接登记到 `permission`，Owner 与 Tenant Admin 随之拥有，也可授予自定义角色。组织接口改为要求 `iam:org:manage`，角色接口要求 `iam:role:manage`；`RoleService.requireTenantAdmin` 改为 `requirePermission`，按数据库当前角色判断。改动 iam 权限码须升级 `IamPermissions` 中的版本。已知取舍：持有 `iam:role:manage` 的成员可以给自己分配 Tenant Admin 或建一个全权限角色，等同管理员，授予时需注意。
- 包依赖：`membership` 不再依赖 `role`，改由 `RoleGrantSource` 接口取角色与权限（与 `SubjectAttributeSource` 同一做法）；角色管理放在单独的 `RoleManagementService`，避免与发布明细的 `MembershipStateService` 形成 Bean 循环。`TenantActor` 移到 `membership.model`，供组织与角色共用。
- 角色模板：`ResourceManifest` 新增可选的 `roles`（`RoleTemplate(code, name, permissions)`），SDK 从清单文件的 `roles` 读取；校验编码格式、不重复、不占用 `owner`/`tenant-admin`，引用的权限码须在本清单声明。为空时不输出该字段，已存储清单的 JSON 比较不受影响。iam 开通租户时在预置角色之后按各服务当前清单的模板建立普通自定义角色（已有同编码时跳过），之后可见、可编辑、可删除；之后新增的模板不会补到已有租户。demo-service 增加模板「订单查看员」（`order:read`），清单版本升到 2。
- 前端：tenant-app 新增 `/roles` 角色管理页（角色列表、新建/编辑对话框中按服务与分组勾选权限码、成员分配多选角色，Owner 不可选）。导航中「组织管理」「角色管理」分别以 `v-perm` 按 `iam:org:manage`、`iam:role:manage` 显示。Shell 原先只在 Membership 变化时重读 `/auth/access`，现改为 Access Token 变化（含刷新）时也重读：授权变更使旧 Token 失效，刷新后菜单与按钮按新权限更新。
- 验证：iam `RoleManagementTest` 4 项（撤销权限后 Redis 明细与 `/auth/access` 菜单同步收回、iam 自身接口返回 403，多角色并集；预置角色保护、保留与重复编码、Owner 不可分配、使用中不可删除、失效权限码不可授予且恢复后授权恢复；模板角色开通幂等且可编辑；租户隔离与无权限 403）；forge-sdk-manifest 新增角色模板解析与校验 1 项、上报 JSON 不含空 `roles` 断言。前端 tenant-app 71 项单测（新增角色 4 项、导航按权限显示 1 项）、typecheck、format:check、build 与 11 项 Playwright 通过。「下一次请求 403」在后端以 iam 自身接口与 Redis 明细验证（demo-service 的 `@RequiresPermission` 按同一明细判断，已由工单 12 覆盖）；未以真实服务经网关在浏览器中手动操作角色页。

