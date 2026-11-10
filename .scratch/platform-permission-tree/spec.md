# 平台侧授权树规格

Status: resolved

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-09 的设计拷问逐条确认，2026-11-10 收缩范围：删除平台角色部分，增加菜单分组不挂权限码。授权树是 Permission Code 的视图，菜单不是授权单位（[ADR-0018](../../docs/adr/0018-menu-is-not-an-authorization-unit.md)）。租户侧授权树见 [menu-permission-tree](../menu-permission-tree/spec.md)，菜单管理见 [navigation-layout](../navigation-layout/spec.md)。

## 1. 背景与目标

Plan 的 Feature Pack 仍是按服务、分组平铺的 Permission Code 列表：Operator 还没把 Sub-App 放进 Plan，就能勾选它的权限码。例如 Plan 没有 `demo`，仍可勾选 `order:*`。

目标：

- Plan 先选可用 Sub-App，再按菜单页面勾选这些 Sub-App 的权限码。
- Plan 与租户侧共用同一套树形编辑组件和勾选规则。
- 菜单分组不挂 Permission Code，权限码只挂在页面上。

非目标：

- 不新增菜单授权维度。Plan 仍只保存 Permission Code 与 Sub-App 编码，写入接口的格式不变。
- 不改平台角色的授权界面，继续使用平铺列表。
- 不改 App Registry 的注册表单，注册表单不录入、不展示菜单。
- 除第 2 节的分组规则外，不改 Resource Manifest 的结构与校验，不改 demo 的清单。
- 不合并不同服务声明的同编码根菜单。
- 不改租户侧角色树的树形与行为。
- 后端不校验「Plan 的权限码是否归属已选 Sub-App」。

## 2. 菜单分组不挂权限码，权限码归属 Sub-App

清单校验（`ResourceManifest.validate()`，SDK 生成与 iam 接收共用）拒绝带 `permission` 的分组节点。分组是否显示只看其下有没有可见页面；整块关闭一个模块用 Plan 的 Sub-App。现有清单（demo、forge-platform）没有带权限码的分组。仓库外已给分组挂权限码的业务服务，升级 SDK 后生成或上报清单会失败，需要把权限码移到页面上。

Permission Code 本身不带 Sub-App，归属由叶子菜单决定：

- 叶子菜单的 `permission` 与 `actions` 归属该叶子的 `app`。
- 一个权限码可同时归属多个 Sub-App，其中任意一个被选中就显示。
- 没有挂在任何叶子菜单上的权限码不归属 Sub-App。

## 3. iam 与 platform：Plan 的授权树接口

iam 新增服务间接口 `GET /internal/permission-tree`，与 `GET /internal/permissions` 使用同一条安全链（须加入 `manifestSecurity` 的 `securityMatcher`），由网关拒绝外部访问：

```json
{
  "menus": [
    { "code": "demo", "name": "示例应用", "icon": "grid", "app": null, "permission": null, "actions": [], "children": [
      { "code": "demo.overview", "name": "订单概览", "icon": null, "app": "demo",
        "permission": { "code": "order:read", "name": "查看订单", "sharedWith": ["demo.attachments"] },
        "actions": [ { "code": "order:create", "name": "创建订单", "sharedWith": [] } ],
        "children": [] }
    ] }
  ],
  "others": [ { "code": "report:sync", "name": "同步报表", "service": "report-service", "group": "报表" } ]
}
```

- 权限范围是 `PermissionService.planScopedPermissions()`，不含 iam 自身的管理权限码。
- 菜单取各服务当前清单，**不按 Plan 或 Sub-App 裁剪**（相当于 `visibleMenus(范围内编码, Optional.empty())`）。权限码不在范围内的节点连同子树隐藏，范围外的 action 去掉，不挂权限码的叶子保留。
- `sharedWith` 按整棵树计算。
- `others`：范围内、未出现在菜单树上的权限码，按服务、分组、编码排序。
- 响应的菜单节点在现有 `PermissionTreeResponse.Menu` 的基础上增加 `app`，分组节点为 null。实现须遵守跨功能包只经由 `service` 访问的规则。

platform 新增 `GET /platform/plans/permission-tree`，权限与现有 Plan 接口相同，透传上述结果；iam 不可用时返回 `plan.catalogUnavailable`。

删除 `GET /platform/plans/permissions` 与前端对应调用。`GET /internal/permissions` 保留，`requireKnown` 与 `TenantPlanImpactService` 仍在服务层使用目录。

## 4. 共用组件：`@crane199709/ui`

把 tenant-app 的 `PermissionTreeEditor.vue` 与 `permission-selection.ts` 提取到 `@crane199709/ui`，按该包现有的组件写法与 local scope i18n 实现。tenant-app 改为引用，行为与 `data-testid` 不变，相关测试随代码迁移。

组件增加两项能力，组件本身不感知 Plan：

- 页面权限显示方式，默认保持租户侧现状（节点的勾选框代表页面权限）。开启「页面权限作为子项」后，根节点只负责全选和半选，页面权限显示为第一个子项。Plan 使用这种方式。
- 由调用方传入额外的权限分组，显示在「功能权限」区之后，供 Plan 显示第 5 节的两个分组。

勾选规则沿用租户侧：

- 勾选页面 = 勾选页面权限及全部操作；操作可逐项取消。
- 勾选任一操作时自动勾上页面权限；取消页面权限时其下操作一并取消。
- 共用的权限码同步勾选并提示。
- 不挂权限码的页面显示「始终可见」，页面本身不可勾选。

## 5. Plan 编辑对话框

对话框把「可用 Sub-App」放到权限选择之前，打开时读取第 3 节的树，读取失败时沿用现有错误提示、不打开对话框。Sub-App 下拉框保持现状，选项来自 App Registry，仍可输入尚未登记的编码。

由 `menus` 和所选 Sub-App 在前端即时计算要显示的树，切换 Sub-App 不重新请求（纯函数，放在 `platform-console` 的 `plan` 目录）：

- 每个 `app` 在所选 Sub-App 中的叶子菜单成为一个根，分组节点不显示，多层分组全部拍平，按清单顺序排列。
- 使用「页面权限作为子项」的显示方式，页面下依次是：页面的 `permission`、页面的 `actions`。
- 选了多个 Sub-App 时，根节点旁用小字显示 Sub-App 编码。
- 没选 Sub-App 时菜单树为空并显示提示。
- 共用提示只列当前显示的页面，未选 Sub-App 的页面不出现。

demo 选中后的结果：

```text
订单概览 ┬ 查看订单 order:read（与订单附件共用）
         ├ 创建订单 order:create
         ├ 修改订单 order:update
         ├ 删除订单 order:delete
         ├ 导入订单 order:import
         └ 导出订单 order:export
订单附件 ─ 查看订单 order:read（与订单概览共用）
```

其他区域：

- 「功能权限」区：`others`，始终显示，不受 Sub-App 选择影响。
- 「所属 Sub-App 未选择」区：当前 Plan 中已勾选、归属的 Sub-App 全部未选中的权限码，保持勾选，可逐项取消。选上对应 Sub-App 后回到树中并保持勾选。
- 「已下线」区：Plan 中已不在目录（树与 `others`）里的权限码，行为同现有实现。

取消某个 Sub-App 时，立即从草稿中移除只归属它的权限码，仍归属其他已选 Sub-App 的码保留。重新选回时默认不勾选，不恢复之前的勾选。「所属 Sub-App 未选择」区里的码不受影响。

提交时把勾选结果去重为 Permission Code 列表，三个分区的码都包含在内，沿用现有创建和更新接口。

## 6. 文档

- 更新 [docs/sub-app.md](../../docs/sub-app.md) 的「发布到租户 Shell」：Plan 中先选 Sub-App，再勾选其权限码；分组不挂权限码。
- [menu-permission-tree 规格](../menu-permission-tree/spec.md)第 6 节的两个待定项已于 2026-11-10 关闭。

## 7. 验收

后端：

- SDK：带权限码的分组被拒绝；`RoleManagementTest` 改写后仍覆盖隐藏规则。
- iam 集成测试：`/internal/permission-tree` 不按 Plan 裁剪，不含 iam 自身管理权限码，`others` 正确，叶子带 `app`；缺少上报令牌时被拒绝。
- platform 集成测试：`/platform/plans/permission-tree` 的透传、权限校验与 iam 不可用时的 `plan.catalogUnavailable`；旧接口已移除。
- `PlanTest` 中已有的写入语义不变。

前端：

- `@crane199709/ui`：覆盖两种页面权限显示方式、勾选联动与额外分组。
- tenant-app：角色树现有测试全部通过，行为无变化。
- Plan 组件测试：
  - 未选 Sub-App 时不显示 demo 的权限码，选中后出现订单概览与订单附件两个根及 6 个权限码。
  - 分组节点不显示，多层分组拍平。
  - 取消 Sub-App 时移除独占的码、保留共用的码；重新选回时不勾选。
  - 存量 Plan 中未选 Sub-App 的码进入独立分区，保存时不丢失。
  - 已下线的码保留。

## Comments

