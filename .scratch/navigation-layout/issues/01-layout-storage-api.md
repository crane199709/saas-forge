# 01: 导航布局的存储、接口与权限码

**What to build:** 按 [spec](../spec.md) 第 2、4、5、6 节，在 SDK 与 iam 完成菜单层级校验、权限码、存储、读写接口和 access 响应。

**Blocked by:** 无

**Status:** ready-for-agent

- [ ] `ResourceManifest.validate()` 拒绝超过三层的菜单，补 SDK 测试
- [ ] 新增 `iam:menu:manage` 与 `platform:menu:manage`，`IamPermissions`、`PlatformPermissions` 升级版本；前端两端的权限码常量同步
- [ ] Flyway：`tenant.navigation_layout`（`jsonb`，可空）与 `platform_navigation_layout`（标准表），按编码规范写中文注释
- [ ] 新功能包 `navigation`：结构校验（三选一、三层、引用唯一、分组 id 与名称长度、节点数 ≤ 500），违反时返回 `navigation.invalidLayout`
- [ ] 租户 `GET`/`PUT`/`DELETE /navigation-layout`，`GET` 带按 Plan 裁剪、去掉 `actions` 的 `menus` 目录
- [ ] 平台 `GET`/`PUT`/`DELETE /platform/navigation-layout`，最多一条未删除记录，恢复默认为逻辑删除
- [ ] `/auth/access` 与 `/platform/access` 增加 `layout`
- [ ] 保存与恢复默认写 `navigationLayout.replace`、`navigationLayout.reset` 审计
- [ ] iam 集成测试覆盖 spec 第 8 节后端各项

## Comments
