# 数据权限（Data Scope 与自定义条件）

工单：[18](../.scratch/saas-forge-mvp/issues/18-data-permission-end-to-end.md)、[19](../.scratch/saas-forge-mvp/issues/19-custom-condition-editor.md)。设计见 [ADR-0006](adr/0006-rbac-functional-abac-data-permissions.md)。功能权限（Permission Code）决定能否调用接口，数据权限决定能看到、改动哪些行。

## 业务服务：声明 Data Resource

在 `META-INF/forge/manifest.json` 中声明受保护的数据资源、所在的表与可用于策略判断的属性，随 Resource Manifest 上报 iam。改动后须升级 `forge.manifest.version`。

```json
"dataResources": [
  {
    "code": "order",
    "name": "订单",
    "table": "orders",
    "attributes": [
      { "code": "ownerId", "name": "归属成员", "type": "uuid", "column": "owner_id" },
      { "code": "departmentPath", "name": "归属部门", "type": "ltree", "column": "department_path" }
    ]
  }
]
```

- `code` 全平台唯一，被其他服务占用时上报返回 409 `manifest.dataResourceConflict`；一张表只能属于一个数据资源。
- `type` 取 `string number boolean uuid ltree`，表名、列名只能是普通标识符。
- Data Scope 预设要求资源声明相应属性：「本人」需要 `uuid` 类型的 `ownerId`，「本部门」「本部门及下级」需要 `ltree` 类型的 `departmentPath`。资源没有声明的预设在角色编辑中不可选。

引入 `forge-sdk-abac` 后，SDK 对该表的查询、更新与删除（含 `BaseMapper` 方法、`LambdaQueryWrapper` 与 mapper XML，JOIN 与子查询中的表也算）自动追加当前成员的数据权限条件，值一律参数绑定。INSERT 不过滤：创建对象时由业务代码设置归属，并用 `DataPermission.check(resource, attributes)` 校验新对象在自己的数据范围内（内存求值，与 SQL 过滤结论一致），否则 403 `auth.forbidden`。`ltree` 列写入时在实体字段上使用 `@TableField(typeHandler = LtreeTypeHandler.class)`。

数据资源表上的 SQL 不要使用 jsonb 的 `?`、`?|`、`?&` 运算符：JDBC 把 `?` 当作占位符，转义写法 `??` 又无法被 SQL 解析器（租户与数据权限拦截器共用）识别，语句会直接报错。改用 `jsonb_exists(col, 'key')`、`jsonb_exists_any`/`jsonb_exists_all` 或 `col @> '["key"]'`。

不应受数据权限过滤的 Mapper 方法（如按租户的批量清理）标注 `@InterceptorIgnore(dataPermission = "true")`。

## 默认行为

| 请求 | 行为 |
| --- | --- |
| 成员请求，持有的 Role 在该资源上**没有任何** Data Policy | **拒绝**：列表为空、按 id 读取、更新与删除视为不存在，单对象校验不通过 |
| 成员请求，有一个或多个 Role 配置了范围 | 各 Role 的范围取并集 |
| Owner、Tenant Admin | 所有数据资源上为「全部」 |
| Operator 请求（Platform Realm 服务） | 规则同成员：按其平台角色上的策略取并集，没有策略时拒绝；超级管理员为「全部」，见 [平台侧权限](platform-permission.md) |
| 没有认证上下文的系统操作（MQ 消费、定时任务、租户清理） | 不受数据权限限制，仍受租户隔离 |

超出范围的对象与不存在的对象一样返回 404（demo 为 `order.notFound`），不泄露其存在。数据归属列为空的行（如接入前的存量数据）只有「全部」范围的成员可见。服务未配置 Redis 时，成员请求访问数据资源以 500 失败，不会静默放行。

## Tenant Admin：在角色上选择范围

tenant-app「角色管理」中编辑自定义角色，为每个数据资源选择「未配置（不可见）」「本人」「本部门」「本部门及下级」或「全部」。「本部门」指成员的主部门与兼任部门，不含下级部门。

| 接口（网关路径） | 说明 |
| --- | --- |
| `GET /iam/roles/data-resources` | 各服务声明的数据资源，及其可用预设 `scopes` 与自定义条件可引用的资源属性 `attributes` |
| `GET /iam/roles/condition-options` | 自定义条件可引用的主体属性 `subject`（含本租户扩展属性）、环境属性 `environment`，以及部门、岗位取值 |
| `POST /iam/roles` | 新建角色时可带 `dataPolicies: [{resource, scope, condition?}]`，与权限码授权同一事务写入；范围不合法时整个新建失败 |
| `PUT /iam/roles/{id}/data-policies` | JSON `policies: [{resource, scope, condition?}]`，整体替换；`scope` 取 `self department department_and_below all`，或 `custom` 并带 `condition`。资源未声明、重复或不支持该预设时 400 `role.invalidDataPolicy`，条件不合法时 400 `role.invalidDataCondition`，预置角色 409 `role.builtin` |
| `POST /iam/roles/data-policies/preview` | JSON `{policy, membershipId, samples}`，以该成员当前的主体属性对样例数据逐条求值，返回 `matches` 与求值用的 `context`，不保存 |
| `GET /iam/roles` | 每个角色带 `dataPolicies`，自定义条件带 `condition` |

范围变更后，持有该角色的成员 version 递增，旧 Token 立即失效，刷新后按新范围生效；成员的部门分配变化同样即时生效（工单 13）。业务服务新声明或移除的数据资源在成员下一次刷新 Token 后生效，移除的资源上的策略保留但不再发布。

## 自定义条件

除预设外，可以为数据资源选择「自定义条件」，在可视化编辑器中用「全部满足 / 任一满足」的条件组嵌套组合条件，条件组可取反。每个角色在每个资源上仍只有一条策略，预设与自定义条件二选一；「本部门及下级」等预设也能在编辑器中表达，便于与其他条件组合，如「金额大于 1000 且属于本部门及下级」：

```json
{"and": [
  {"attr": "resource.amount", "op": "gt", "value": 1000},
  {"attr": "resource.departmentPath", "op": "under", "value": {"ref": "subject.departmentPaths"}}
]}
```

可引用的属性：

| 命名空间 | 来源 |
| --- | --- |
| `resource.<编码>` | 业务服务在 Resource Manifest 中声明的资源属性 |
| `subject.membershipId` `subject.departmentPaths` `subject.primaryDepartmentPath` `subject.positionIds` | 组织模型：当前成员、所在部门（主部门与兼任部门）、主部门、担任岗位 |
| `subject.attributes.<编码>` | 本租户定义的 Extended Attribute，类型 `string number boolean` |
| `env.now` | 请求时刻的 Unix 时间戳（秒） |

编辑器按属性类型限制操作符与取值控件：大小比较只用于数值，「在部门及下级中」只用于部门路径，集合属性（所在部门、担任岗位）只能判断包含或为空；取值可以是固定值，也可以引用类型相符的成员或环境属性。

保存前 iam 按与业务服务 SDK 相同的属性目录校验条件，结构错误、属性未声明、操作符不适用或取值类型不符时返回 400 `role.invalidDataCondition`，提示出错节点的位置（JSON Pointer）与原因。条件 JSON 不超过 8192 个字符。

「预览命中情况」选择一个成员并填写样例数据，iam 以该成员当前的组织属性、扩展属性与此刻的环境属性逐条求值，与业务服务的单对象校验使用同一个求值器。属性为空的比较按 SQL 三值逻辑视为不命中。

扩展属性的定义类型随成员的主体属性一起发布。删除被条件引用的扩展属性后，该条件在成员的明细重新发布后加载失败，业务服务按拒绝处理并记录错误日志，不会放宽可见范围；之后保存该角色的数据范围也会被拒绝，需要在角色上改掉该条件。
