# 平台侧权限

工单：[22](../.scratch/saas-forge-mvp/issues/22-platform-side-permissions.md)，决策见 [ADR-0003](adr/0003-iam-dual-realm.md)、[ADR-0005](adr/0005-immediate-revocation-via-membership-version.md)、[ADR-0006](adr/0006-rbac-functional-abac-data-permissions.md)。平台侧与租户侧共用同一套权限引擎（Resource Manifest、`@RequiresPermission`、按版本发布的明细、ABAC 表达式树与 SQL 拦截器），数据完全分离。

## 数据分离

| | Tenant Realm | Platform Realm |
| --- | --- | --- |
| 清单 | Business Service 上报，不带 `realm` | Platform Realm 服务（forge-platform）在 `META-INF/forge/manifest.json` 中写 `"realm": "platform"`；iam 自身的平台管理权限码登记为服务 `forge-iam-platform` |
| 权限码 | `permission.realm = 'tenant'` | `permission.realm = 'platform'`，编码仍全平台唯一 |
| 角色 | `role`、`membership_role`、`role_permission`、`role_data_policy` | `platform_role`、`operator_role`、`platform_role_permission`、`platform_role_data_policy`（iam V17） |
| 预置角色 | Owner、Tenant Admin | 超级管理员 `super-admin` |
| 明细（Redis） | `iam:<tenant_id>:membership:<id>:{version,grants:<ver>}` | `iam:platform:operator:<id>:{version,grants:<ver>}`，值同为 `MembershipGrants` |

租户角色、套餐 Feature Pack 与菜单只看得到 Tenant Realm 的权限码与数据资源；平台角色只看得到 Platform Realm 的。直接调用接口授予另一个 Realm 的权限码返回 400。数据资源编码在两个 Realm 之间同样不能重复。

## 权限码

| 权限码 | 服务 | 接口 |
| --- | --- | --- |
| `platform:tenant:read` | forge-platform | `GET /platform/tenants`、`GET /platform/tenants/{id}` |
| `platform:tenant:manage` | forge-platform | 创建租户、变更状态、更换套餐、重试初始化 |
| `platform:plan:manage` | forge-platform | 新建、修改、删除套餐，`GET /platform/plans/permissions` |
| `platform:sub-app:manage` | forge-platform | 登记、修改、删除 Sub-App |
| `platform:operator:manage` | iam | `/iam/platform/operators/**` |
| `platform:role:manage` | iam | `/iam/platform/roles/**`（角色列表也对 `platform:operator:manage` 开放，用于分配） |
| `platform:audit:read` | iam | `GET /iam/platform/audit/events`，见[审计](audit.md) |

`GET /platform/plans` 与 `GET /platform/sub-apps` 只要求已认证：租户与套餐面板需要据此选择。

## 超级管理员

- 拥有全部有效的平台权限码，在全部平台数据资源上为「全部」，不落授权记录，新上报的权限码自动拥有。
- 不能改名、修改授权或删除（409 `platformRole.builtin`），编码 `super-admin` 不能被占用。
- 始终保留至少一位状态正常的超级管理员：停用或撤销最后一位时 409 `platformRole.lastSuperAdmin`。
- 只有超级管理员能分配超级管理员角色，或编辑、停用、恢复、重置密码、调整角色一位超级管理员；其他持有 `platform:operator:manage` 的 Operator 这样做时 403，避免借账号管理权限提权。
- V17 让升级前已有的 Operator 都成为超级管理员，保持此前「任一 Operator 可管理全部」的能力；demo 的演示运营同样是超级管理员。

## 数据范围

forge-platform 申报数据资源 `tenant`（表 `tenant`），属性：`ownerId`（创建人 `created_by`，uuid）、`region`（区域，string）、`status`（状态，string）。可用预设为「本人创建」（`self`）与「全部」（`all`），没有归属部门，部门类预设不可用。

Operator 的主体属性：`subject.membershipId` 为 Operator 标识（「本人」预设因此表示创建人是当前 Operator），`subject.attributes.region` 为 Operator 的负责区域。platform-console 的「负责区域」选项保存为自定义条件：

```json
{"attr":"resource.region","op":"eq","value":{"ref":"subject.attributes.region"}}
```

自定义条件的校验规则与租户角色相同（错误码沿用 `role.invalidDataPolicy`、`role.invalidDataCondition`）。默认行为同 [数据权限](data-permission.md)：没有任何策略的 Operator 看不到任何租户；范围外的租户与不存在的一样返回 404；新建租户与状态迁移按变更后的属性再判断一次，须仍在自己的范围内，否则 403（如范围限定为「正常或已停用」时不能把租户改为已过期）。Operator 未分配区域时，「负责区域」条件不命中任何租户；区域上线前的存量租户没有区域，只有「全部」或「本人创建」可能命中。

套餐是否被使用、修改套餐后向租户广播不受操作人的数据范围影响（Mapper 方法标注 `@InterceptorIgnore(dataPermission = "true")`）。

## 即时生效

Operator 表增加 `version`。以下变更在事务提交后递增版本并发布到 Redis，网关比对 Platform Realm Token 的 `ver`，不一致返回 401，前端刷新后按新权限签发：

- 停用（刷新同时被拒，需恢复后重新登录）
- 平台角色分配变化
- 角色的授权或 Data Policy 变化（影响全部持有者）
- 负责区域变化
- 重置密码（已签发的 Access Token 失效）

iam 自身的平台管理接口按数据库中的当前状态判断权限与 Operator 是否 active，不依赖 Token 刷新。platform-console 在 Token 变化时重新读取 `GET /iam/platform/access`，面板按权限显示或隐藏。

## 接口（网关路径）

| 接口 | 说明 |
| --- | --- |
| `GET /iam/platform/access` | 当前 Operator 的平台角色与有效权限码 |
| `GET /iam/platform/operators` | 运营账号及其平台角色 |
| `POST /iam/platform/operators` | `account`、`displayName`、`password`、`region`、`roleIds`；账号被占用 409 `operator.accountTaken` |
| `PUT /iam/platform/operators/{id}` | `displayName`、`region` |
| `POST /iam/platform/operators/{id}/disable`、`/enable` | 停用、恢复 |
| `PUT /iam/platform/operators/{id}/password` | 管理员重置密码 |
| `PUT /iam/platform/operators/{id}/roles` | 整体替换平台角色 |
| `GET /iam/platform/roles` | 平台角色及其授权与 Data Policy |
| `GET /iam/platform/roles/permissions`、`/data-resources` | 可授予的平台权限码、可配置的平台数据资源 |
| `POST /iam/platform/roles`、`PUT /iam/platform/roles/{id}`、`DELETE /iam/platform/roles/{id}` | 新建、整体修改（名称、授权、Data Policy）、删除；仍有持有者时 409 `platformRole.inUse` |

## 已知限制

- 重置密码不终止已有的登录会话：Refresh Token 仍可换发新 Token，需要立即踢出时应停用账号。
- 平台角色没有自定义条件的可视化编辑器与命中预览，platform-console 以 JSON 输入自定义条件，由服务端校验。
- Operator 的负责区域只有一个值；业务服务新增或下线平台权限码、数据资源时不递增版本，Operator 下一次刷新 Token 后生效，与租户侧一致。

## 验收

后端：iam `PlatformRoleTest`（超级管理员与两侧权限码分离、受限 Operator 的明细、授权/区域/角色/停用/重置密码递增版本、最后一位超级管理员保护）、forge-platform `PlatformPermissionTest`（清单、按区域与本人范围过滤租户、无权限 403、范围外 404、范围外创建 403、不能把租户迁移出范围、套餐用量不受范围影响）、网关 `GatewaySecurityTest`（Operator 版本变化或缺失时 401、`/iam/platform/**` 只接受 Platform Realm Token）、forge-sdk-security `PlatformRequiresPermissionTest`。前端：platform-console 的 `App.spec.ts`、`OperatorPanel.spec.ts`、`PlatformRolePanel.spec.ts`。
