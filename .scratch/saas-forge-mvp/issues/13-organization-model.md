# 13: 组织模型

**What to build:** Tenant Admin 在 tenant-app 维护本租户的 Department 树、Position 与 Extended Attribute 定义，并给 Membership 分配主部门、兼任部门、岗位与扩展属性值。这些数据可供后续数据权限作为主体属性使用。

**Blocked by:** 06 多租户成员与切换租户

**Status:** ready-for-agent

- [x] Department 树以 PostgreSQL `ltree` 存储层级，支持新增、改名、移动（含子树）、删除（有成员或子部门时拒绝）
- [x] Position 的增删改
- [x] Extended Attribute 定义按租户维护（名称、类型、是否必填），值以 JSONB 存于 Membership
- [x] 一个 Membership 有且仅有一个主部门，可兼任多个部门
- [x] 组织数据严格按租户隔离
- [x] SDK 能为当前 Membership 提供主体属性（所在部门路径集合、岗位、扩展属性）
- [x] tenant-app 提供部门树、岗位、扩展属性与成员分配的管理界面
- [x] 测试：移动部门后其子树路径正确更新；主体属性随分配变化


## Comments

2026-10-27 实现：
- 数据：V8 新增 `department`（`path ltree`，标签为去掉连字符的 id，改名不变、移动时整棵子树改写）、`position`、`extended_attribute`（编码与类型创建后不可改），`membership.attributes` 为 JSONB 对象；`membership_department`（`is_primary`，每成员至多一个主部门）与 `membership_position` 为纯关联表。与 membership、role 一样不启用 RLS，隔离靠 mapper 中取自 Token 的 `tenant_id` 条件，跨表引用一律走 `(tenant_id, id)` 复合外键，数据库层面就分配不到其他租户的部门或岗位。`ltree` 由 `deploy/postgres/init/01-service-schemas.sql` 以超级用户装在 `extensions` schema，服务账号的 search_path 改为 `<svc>, extensions`。**已初始化过的本地库不会重跑 init 脚本，需要重建数据卷，或以超级用户手动执行该脚本中 extensions 相关的语句并修改两个账号的 search_path，否则 V8 会失败。**
- iam `organization` 功能包：`/org/departments`（含 `PUT /{id}/parent` 移动）、`/org/positions`、`/org/extended-attributes`、`GET /org/memberships`（分页）与 `PUT /org/memberships/{id}/assignment`（整体替换）。只有 Owner 与 Tenant Admin 可调用（`RoleService.requireTenantAdmin`，TODO #16 改为按权限码）。组织写操作在同一租户内以咨询锁串行，避免移动子树与新增下级、删除与分配的竞争。部门有子部门或成员、岗位仍有人担任时拒绝删除；删除扩展属性定义会移除各成员的该值。有兼任部门时必须指定主部门；主部门与兼任部门都为空表示不属于任何部门（新成员的初始状态）。改为必填不回溯校验已有成员，成员下次保存分配时才要求填写。
- 主体属性：sdk-core 新增 `SubjectAttributes`，作为 `MembershipGrants.subject` 与角色、权限同版本发布；缺失时（旧明细）视为未分配。`toContext` 给出 ABAC 键 `subject.membershipId`、`subject.departmentPaths`、`subject.primaryDepartmentPath`、`subject.positionIds`、`subject.attributes.<code>`，`MembershipGrantStore.currentSubject()` 返回当前请求的主体上下文。分配变化、移动部门影响到的成员、删除属性定义影响到的成员，都在事务提交后递增 version（`SubjectChangePublisher`），旧 Token 立即失效。membership 包经 `SubjectAttributeSource` 接口取主体属性，由 organization 包实现，避免两个功能包互相依赖。
- 网关放行 `/iam/org/**`（需 Tenant Realm Token）。tenant-app 新增 `/org` 组织管理页（部门树、岗位、扩展属性、成员分配四个页签），导航在登录后显示，无权限时展示服务端 userTip。
- 已知遗留：移除成员（工单 17）时需一并清除其部门与岗位分配，否则残留分配会阻止删除部门或岗位；批量递增 version 中途 Redis 失败时，剩余成员要等刷新 Token 才更新主体属性，与既有的「Redis 失败需重试变更」约定一致。工单 12 遗留的网关未放行 `GET /iam/auth/access` 已在后续提交中修复：网关对 iam 改为默认要求 Tenant Token，匿名接口显式列出，`/iam/oauth2/**` 与 `/iam/internal/**` 显式拒绝。
- 验证：forge-sdk-security 新增 2 项（主体属性读取、旧明细兼容）；iam `OrganizationTest` 4 项（移动后子树路径、主体属性随分配/移动/删除定义变化、删除规则、租户隔离与 403）；网关新增 1 项；`TenantLoginTest` 断言随明细格式更新。后端 `./mvnw verify` 全部通过；前端 63 项单测（新增组织 6 项）、typecheck、format:check、构建与 11 项 Playwright 通过。未以真实服务经网关在浏览器中手动操作组织页。
- 双轴审查后修复：Mapper 文档注释补齐 Data Policy 说明；成员列表响应改为 `MemberAssignmentResponse`；`MembershipService.updateAttributes` 不再抛自建异常；创建扩展属性纳入租户锁。
