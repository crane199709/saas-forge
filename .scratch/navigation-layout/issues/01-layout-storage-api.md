# 01: 导航布局的存储、接口与权限码

**What to build:** 按 [spec](../spec.md) 第 2、4、5、6 节，在 SDK 与 iam 完成菜单层级校验、权限码、存储、读写接口和 access 响应。

**Blocked by:** 无

**Status:** resolved

- [x] `ResourceManifest.validate()` 拒绝超过三层的菜单，补 SDK 测试
- [x] 新增 `iam:menu:manage` 与 `platform:menu:manage`，`IamPermissions`、`PlatformPermissions` 升级版本；前端两端的权限码常量同步
- [x] Flyway：`tenant.navigation_layout`（`jsonb`，可空）与 `platform_navigation_layout`（标准表），按编码规范写中文注释
- [x] 新功能包 `navigation`：结构校验（三选一、三层、引用唯一、分组 id 与名称长度、节点数 ≤ 500），违反时返回 `navigation.invalidLayout`
- [x] 租户 `GET`/`PUT`/`DELETE /navigation-layout`，`GET` 带按 Plan 裁剪、去掉 `actions` 的 `menus` 目录
- [x] 平台 `GET`/`PUT`/`DELETE /platform/navigation-layout`，最多一条未删除记录，恢复默认为逻辑删除
- [x] `/auth/access` 与 `/platform/access` 增加 `layout`
- [x] 保存与恢复默认写 `navigationLayout.replace`、`navigationLayout.reset` 审计
- [x] iam 集成测试覆盖 spec 第 8 节后端各项

## Comments

- 实现说明：`/platform/access` 从 `platformrole` 移到 `access` 功能包。`navigation` 要依赖 `platformrole.service` 校验权限，接口留在原处会形成功能包循环。导航布局的 SQL 放在 `navigation` 自己的 Mapper 中，直接读写 `tenant.navigation_layout` 列。tenant-app 没有权限码常量模块，页面权限码直接写在 `router.ts`/`App.vue` 中，所以 `iam:menu:manage` 的入口和路由由工单 03 添加；本工单只同步了 platform-console 的 `permissions` 常量，以及两端视觉测试中的权限列表。
- 结构校验额外拒绝空白的 `page`/`menu` 引用和空白的分组名称；`page` 节点只拒绝非空的 `children`，`[]` 视为没有子节点。
