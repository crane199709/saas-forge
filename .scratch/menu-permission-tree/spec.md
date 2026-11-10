# 角色授权菜单树规格

Status: resolved

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-10-30 的设计拷问逐条确认，核心决策由 [ADR-0018](../../docs/adr/0018-menu-is-not-an-authorization-unit.md) 记录。

## 1. 背景与目标

租户的角色授权界面目前是一串平铺的 Permission Code，管理员难以对应到页面；菜单可见性完全由菜单挂的 Permission Code 决定，iam 中没有角色与菜单的关联。

目标：租户管理员按「菜单 → 页面内操作」的树为角色勾选权限，例如让某角色能查看、创建订单但不能删除。

非目标：

- 不新增菜单授权维度，Permission Code 仍是唯一授权单位（ADR-0018）。挂同一权限码的多个菜单不能分开授权，需要时由业务服务拆分权限码。
- 不改平台角色的授权界面（Platform Realm），租户侧验证后再定。
- 平台在 Plan 中「指定菜单」暂不做，见第 6 节。

## 2. Resource Manifest：叶子菜单的 `actions`

`ResourceManifest.MenuNode` 新增可选字段 `actions`：该页面内的操作权限码（按钮、页签、导出等）。

```json
{ "code": "demo.overview", "name": "订单概览", "app": "demo", "path": "overview",
  "permission": "order:read", "actions": ["order:create", "order:update", "order:delete"] }
```

- 缺省为空列表；为空时不输出到 JSON（`@JsonInclude(NON_EMPTY)`）。iam 按 JSON 内容判断同一版本是否重复上报，已上报的服务不补写 `actions` 时无需改动、无需升级版本。
- 校验（SDK 生成与 iam 接收共用 `validate()`）：只允许叶子节点带 `actions`；其中的权限码须在本清单中声明；列表内不重复；不能包含本节点的 `permission`。
- 同一权限码可以出现在多个页面的 `actions` 中，也可以既是某菜单的 `permission` 又是另一页面的 action。
- `/auth/access` 返回的菜单去掉 `actions`，Shell App 不需要它，前端 `MenuNode` 类型不变。

## 3. iam：授权树接口

`GET /roles/permission-tree`，权限与现有 `GET /roles/permissions` 相同（`iam:role:manage`），返回当前租户可授予的授权树：

```json
{
  "menus": [
    { "code": "demo", "name": "示例应用", "icon": "grid", "children": [
      { "code": "demo.overview", "name": "订单概览",
        "permission": { "code": "order:read", "name": "查看订单", "sharedWith": ["demo.attachments"] },
        "actions": [ { "code": "order:create", "name": "创建订单", "sharedWith": [] } ] }
    ] }
  ],
  "others": [ { "code": "iam:member:manage", "name": "成员管理", "service": "forge-iam", "group": "成员" } ]
}
```

- 菜单取各服务当前清单（`PermissionService.currentMenus()`），按 Plan 裁剪，不按当前用户的授权裁剪：
  - 权限码不在 `RoleService.grantablePermissions(tenantId)` 中的菜单节点连同子树隐藏；所属 Sub-App 不在 Plan 中的叶子隐藏；子节点全部隐藏的分组节点隐藏。规则与 `AccessService` 一致，直接隐藏而不是置灰。
  - 不可授予的 action 从列表中去掉。
  - 不挂权限码的叶子菜单保留，`permission` 为 null，界面显示为「始终可见」。
- `sharedWith`：同一权限码在树中出现的其他菜单编码（作为 `permission` 或 action），供界面联动勾选与提示。
- `others`：可授予、但没有出现在可见树中的权限码（包括 iam 自身管理权限码、未写进任何 `actions` 的权限码、所在菜单因 Sub-App 不在 Plan 而隐藏的权限码），按服务、分组、编码排序。
- 授权的读写接口不变：角色的授权仍是 Permission Code 列表，`PUT /roles/{id}/permissions` 的校验不变。

## 4. 租户端：角色页的树形授权

角色页（`frontend/apps/tenant-app/src/role/`）的新建与编辑授权改为树形：上半部分为菜单树，叶子下列出 actions；下半部分「功能权限」按服务、分组列出 `others`。

勾选规则（只在界面上，后端不额外约束）：

- 直接勾选叶子菜单 = 授予其 `permission` 及该页面的全部 `actions`；操作权限可逐项取消，页面仍保持勾选。勾选分组 = 勾选全部子孙；不挂权限码的叶子不可勾选。
- 勾选 action 时自动勾上所在页面；取消页面时其下 actions 一并取消。
- 同一权限码出现在多处时同步勾选，并提示「与『<其他菜单名>』共用权限，会一起显示」。
- 每个 action 与 `others` 中的权限码都能单独勾选或取消。
- 提交时把勾选结果去重为 Permission Code 列表，沿用现有接口。

## 5. 业务服务约定

- 需要按页面授权的操作，在叶子菜单的 `actions` 中列出，并在清单 `permissions` 中给出名称；按钮仍须用 `v-perm`、接口仍须用 `@RequiresPermission`，授权树只记录勾选。
- 需要分开控制的菜单挂不同的权限码；没有后端接口的权限码只在清单文件中声明即可。

## 6. 待定（2026-11-10 关闭）

- 平台在 Plan 中「指定菜单」：不做菜单维度，Plan 按 Sub-App 与页面勾选 Permission Code，见 [platform-permission-tree](../platform-permission-tree/spec.md)。
- 平台角色授权界面是否采用同样的树：暂不做，继续使用平铺列表。

## 完成记录

2026-10-31：tickets 01–03 均已完成并记录验证结果。最终 Standards 审查提出的重复菜单裁剪已集中到共享服务；Spec 审查无确定问题。相关 HTTP 与架构回归共 35 项及 Spotless 检查通过。
