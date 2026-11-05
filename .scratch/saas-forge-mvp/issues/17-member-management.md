# 17: 成员管理

**What to build:** Tenant Admin 在 tenant-app 按邮箱或手机号邀请成员加入本租户（已存在的 Identity 收到邀请，不存在的收到注册邀请），可以停用、恢复、移除成员；Owner 可以把所有者身份转让给另一个成员。停用立即生效。Tenant Admin 不能修改成员的全局凭证。

**Blocked by:** 14 租户创建与开通, 16 租户角色管理

**Status:** resolved

- [x] 邀请复用开通流程中的邀请机制，可在邀请时预设角色与部门
- [x] 成员列表展示 Membership 状态（邀请中、正常、停用、已离开），支持搜索与筛选
- [x] 停用、恢复、移除成员；停用即时生效
- [x] 成员可主动退出租户（Owner 除外）
- [x] Owner 转让后原 Owner 降为 Tenant Admin；租户始终恰有一个 Owner
- [x] Tenant Admin 无法修改 Identity 的密码、邮箱、手机号
- [x] 测试：停用成员下一次请求被拒；Owner 不可被停用或移除


## Comments

2026-10-28 实现：
- 数据：V12 给 `membership.status` 加 CHECK，取值 `invited | active | suspended | left`。停用沿用工单 07 已写入的 `suspended`（规格中的 `disabled`）；移除与主动退出都改为 `left` 而不是逻辑删除，列表仍展示「已离开」，再次邀请时同一行回到 `invited`。`left` 时清空 Extended Attribute 值，角色（Owner 除外）与部门、岗位分配在同一事务内清除。`MembershipService.exists` / `page` / `count` 改为排除已离开的成员，`exists` 加 `FOR SHARE`，与并发的移除串行，已离开的成员不能再被分配角色或组织。
- 权限：iam 清单升到 `forge-iam@2`，新增 `iam:member:manage`（管理成员），Owner 与 Tenant Admin 随之拥有，也可授予自定义角色。
- iam 新功能包 `member`（只依赖 membership、role、organization、invitation、identity、tenant 的 service，无循环）：`GET /members`（分页，含已离开；`keyword` 在账号、昵称、邮箱、手机号中按子串匹配，`status` 筛选）、`POST /members/invitations`（邮箱或手机号，可预设角色与主部门，复用开通的 `InvitationService`：已有 Identity 收到加入邀请，没有的建 pending Identity 并收到注册邀请；已是正常/停用成员 409 `member.alreadyMember`，邀请中的重发）、`POST /members/{id}/suspend`、`POST /members/{id}/resume`、`DELETE /members/{id}`（移除或撤回邀请）、`POST /members/me/leave`（主动退出，只需登录）、`PUT /members/owner`（Owner 转让）。Owner 不可停用、移除或退出（409 `member.ownerProtected`）；只有 Owner 能转让（403 `member.notOwner`），对象须是另一个 active 成员（400 `member.invalidTransferTarget`），转让后原 Owner 降为 Tenant Admin。状态变更与转让都先取角色的租户咨询锁再动 Membership 行，与角色分配加锁顺序一致；Owner 判断与停用、移除之间不会插入转让，`membership_role` 的部分唯一索引保证至多一个 Owner、同一事务内先撤后授保证至少一个。状态变化都递增 version，并在事务提交后发布到 Redis：停用、移除、退出后下一次请求被网关拒绝、刷新失败；恢复后需重新登录或刷新。
- 预设角色、部门分别要求 `iam:role:manage`、`iam:org:manage`，在建立 Membership 的事务提交后经 `RoleManagementService.assign` 与新增的 `AssignmentService.assignPrimaryDepartment`（只替换部门，不要求填写必填扩展属性）写入，最后发邀请。没有放进同一事务：这两个方法提交后才能发布版本，嵌套进外层事务回滚时 Redis 版本会领先数据库。
- Tenant Admin 不能修改 Identity 的密码、邮箱、手机号：成员接口只改 Membership，不提供任何修改 Identity 的入口，列表中的联系方式只读。
- 前端：tenant-app 新增 `/members` 成员管理页（搜索、状态筛选、邀请对话框按权限展示预设角色与主部门、停用/恢复/移除/转让确认），导航按 `v-perm="'iam:member:manage'"` 显示；顶部租户切换器旁新增「退出当前租户」，成功后结束会话回到登录。
- 验证：iam `MemberManagementTest` 4 项（真实登录后停用：Redis 版本前进、刷新 401，恢复后重新登录的 ver 等于当前版本；Owner 不可停用/移除/退出，非 Owner 与非法对象不能转让，转让后恰有一个 Owner 且原 Owner 降为 Tenant Admin、双方版本递增；邀请新联系方式预设角色与部门、重复邀请规则、移除后角色清除与旧链接失效、再次邀请回到同一 Membership 并接受；无权限 403、PUT/PATCH 成员接口改联系方式与密码被拒且资料不变、主动退出清除角色并递增版本、已离开成员不能再分配角色）。后端 `./mvnw -pl forge-iam verify` 69 项全部通过。前端 tenant-app 75 项单测（新增成员页 3 项、退出租户 1 项）、typecheck、format:check、build 通过。「下一次请求被拒」以 Redis 版本与 Token `ver` 不一致验证，比对由网关完成（已由 GatewaySecurityTest 覆盖），未经网关真链路。Playwright 未运行：本机 5174/5175 端口已被正在运行的 dev server 占用。
- 已知限制：邀请令牌只在 Redis 中按摘要指向 Membership，撤回邀请后旧链接失效，但同一成员再次被邀请时，7 天内未过期的旧链接会随状态回到 invited 而重新可用（仍只能由该联系方式的持有人使用）；预设角色或部门失败时 Membership 已是 invited 而邀请未发出，修正后再次邀请即可；「转让所有者」按钮对所有正常成员行显示，非 Owner 点击由服务端拒绝。
