# 22: 平台侧权限

**What to build:** 平台侧本身是一套完整的管理系统。platform 服务像 Business Service 一样上报 Resource Manifest；Platform Realm 内置「超级管理员」；超级管理员在 platform-console 管理 Operator 账号与平台角色，为角色授予权限码与数据范围，使 Operator 的平台功能与平台数据（如租户列表）受 RBAC + ABAC 约束。

**Blocked by:** 08 Platform Realm 与运营登录, 16 租户角色管理, 18 数据权限端到端（Data Scope 预设）

**Status:** ready-for-agent

- [x] platform 服务的接口声明权限码并上报清单
- [x] 内置超级管理员角色，不可删除
- [x] platform-console 提供 Operator 账号管理（创建、停用、重置密码）与平台角色管理
- [x] 平台角色可配置数据范围（如按 Operator 负责的区域或自定义条件限定可见租户）
- [x] 平台侧权限与租户侧权限数据完全分离，共用同一套引擎
- [x] Operator 停用或角色变更即时生效
- [x] 测试：受限 Operator 只能看到其范围内的租户；无权限操作被拒


## Comments

2026-10-29 实现（详见 docs/platform-permission.md）：
- 清单：`ResourceManifest` 新增可选的 `realm`，清单文件写 `"realm": "platform"` 即为 Platform Realm 服务（不能声明角色模板）；Tenant Realm 服务不输出该字段，已存储清单的内容比较不受影响。iam V17 给 `permission` 加 `realm`，租户角色、套餐目录与菜单只读 Tenant Realm 的权限码与数据资源，平台角色只读 Platform Realm 的；数据资源编码跨 Realm 也不能重复。forge-platform 接入 forge-sdk-manifest/abac 与 Redis，申报 `platform:tenant:read|manage`、`platform:plan:manage`、`platform:sub-app:manage` 与数据资源 `tenant`（`ownerId`→`created_by`、`region`、`status`）；iam 自身的 `platform:operator:manage`、`platform:role:manage` 登记为服务 `forge-iam-platform`。
- 数据分离：平台角色、Operator 的角色分配、平台角色授权与 Data Policy 各自建表（`platform_role`、`operator_role`、`platform_role_permission`、`platform_role_data_policy`），与租户角色表无关联。超级管理员 `super-admin` 是唯一预置平台角色，由 CHECK 绑定，拥有全部有效平台权限码与全部数据，不可修改或删除；始终保留至少一位状态正常的超级管理员。V17 让已有 Operator 都成为超级管理员（此前任一 Operator 可管理全部），demo 种子同样分配。
- 共用引擎：Operator 明细沿用 `MembershipGrants`，按 `OperatorStateKeys`（`iam:platform:operator:<id>:...`）随版本发布；主体属性中 `subject.membershipId` 为 Operator 标识（「本人」预设即本人创建），`subject.attributes.region` 为负责区域。SDK `@RequiresPermission` 在 Platform Realm 服务中按 Operator 明细判断（移除了一律拒绝的 TODO），`DataPermission` 对 Operator 请求按其策略过滤；Platform Realm Token 增加 `ver`，`PlatformJwtValidator` 要求其存在。数据策略校验复用 `DataConditionService` 的规则与错误码。
- 即时生效：`operator.version` 在停用、角色分配、角色授权或策略、负责区域变化以及重置密码后于事务提交后递增并发布；网关对 `/platform/**` 与 `/iam/platform/**` 校验 Operator 版本（缺失或不一致 401）。iam 平台管理接口按数据库当前状态判断权限与 active。
- iam 接口：`/platform/access`、`/platform/operators/**`（创建、编辑、停用/恢复、重置密码、分配角色）、`/platform/roles/**`，以 Platform Realm 解码器单独成链。forge-platform 的租户列表、读取、修改按 Operator 范围过滤，范围外 404，范围外创建 403；套餐用量判断与修改后的广播豁免数据权限。租户新增 `region`（platform V6）。
- 前端：platform-console 读取 `/iam/platform/access`，按权限码显示套餐、注册表、租户、运营账号、平台角色面板，Token 变化时重读；租户面板支持区域与只读模式；新增运营账号面板与平台角色面板（权限勾选、数据范围预设/负责区域/JSON 自定义条件）。
- 审查后修复：只有超级管理员能分配超级管理员、操作超级管理员的账号（否则 `platform:operator:manage` 即可提权）；网关拒绝 `/iam/platform/oauth2/**`（JWKS 除外）；`/platform/access` 对已停用 Operator 返回空权限；Mapper 方法逐个注明是否受 Data Policy 过滤。
- 验证：后端 `./mvnw verify` 全部通过（新增 iam `PlatformRoleTest` 3 项、forge-platform `PlatformPermissionTest` 5 项、网关 2 项、forge-sdk-security `PlatformRequiresPermissionTest` 2 项、清单 Realm 1 项；审查修复后 iam 与网关再次全量通过）。前端 typecheck、format:check 与全部单测通过（platform-console 20 项，新增 App 权限显示 1 项、运营账号 2 项、平台角色 3 项）。未以真实服务经网关在浏览器中手动操作，未运行 Playwright（没有平台侧用例）。
- 已知限制：按 `status` 限定范围时，可把租户迁移出自己的范围；重置密码不终止已有 Refresh Token（需踢出时停用）；平台角色无条件可视化编辑器与预览；负责区域单值；请求体校验先于权限校验，越权但请求体非法时返回 400。
