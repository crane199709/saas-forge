# 数据权限（Data Scope）

工单：[18](../.scratch/saas-forge-mvp/issues/18-data-permission-end-to-end.md)。设计见 [ADR-0006](adr/0006-rbac-functional-abac-data-permissions.md)。功能权限（Permission Code）决定能否调用接口，数据权限决定能看到、改动哪些行。

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

不应受数据权限过滤的 Mapper 方法（如按租户的批量清理）标注 `@InterceptorIgnore(dataPermission = "true")`。

## 默认行为

| 请求 | 行为 |
| --- | --- |
| 成员请求，持有的 Role 在该资源上**没有任何** Data Policy | **拒绝**：列表为空、按 id 读取、更新与删除视为不存在，单对象校验不通过 |
| 成员请求，有一个或多个 Role 配置了范围 | 各 Role 的范围取并集 |
| Owner、Tenant Admin | 所有数据资源上为「全部」 |
| 非成员的已认证请求（Platform Realm） | 拒绝，平台侧权限见工单 22 |
| 没有认证上下文的系统操作（MQ 消费、定时任务、租户清理） | 不受数据权限限制，仍受租户隔离 |

超出范围的对象与不存在的对象一样返回 404（demo 为 `order.notFound`），不泄露其存在。数据归属列为空的行（如接入前的存量数据）只有「全部」范围的成员可见。服务未配置 Redis 时，成员请求访问数据资源以 500 失败，不会静默放行。

## Tenant Admin：在角色上选择范围

tenant-app「角色管理」中编辑自定义角色，为每个数据资源选择「未配置（不可见）」「本人」「本部门」「本部门及下级」或「全部」。「本部门」指成员的主部门与兼任部门，不含下级部门。

| 接口（网关路径） | 说明 |
| --- | --- |
| `GET /iam/roles/data-resources` | 各服务声明的数据资源及其可用预设 `scopes` |
| `PUT /iam/roles/{id}/data-policies` | JSON `policies: [{resource, scope}]`，整体替换；`scope` 取 `self department department_and_below all`。资源未声明、重复或不支持该预设时 400 `role.invalidDataPolicy`，预置角色 409 `role.builtin` |
| `GET /iam/roles` | 每个角色带 `dataPolicies` |

范围变更后，持有该角色的成员 version 递增，旧 Token 立即失效，刷新后按新范围生效；成员的部门分配变化同样即时生效（工单 13）。业务服务新声明或移除的数据资源在成员下一次刷新 Token 后生效，移除的资源上的策略保留但不再发布。
