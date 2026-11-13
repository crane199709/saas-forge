# 00: 待办、通知与公告读取权限

**What to build:** 按 [spec](../spec.md) 2026-11-13 需求方确认的范围扩展，新增 `todo:read`、`notification:read`、`announcement:read`，使工作台与既有读取入口共享现有授权和套餐裁剪机制。

**Blocked by:** —

**Status:** resolved

- [x] Resource Manifest / 核心权限声明与生成目录登记三种读取权限
- [x] 待办列表、通知列表/未读数/已读操作、公告列表/详情/未读数/已读操作分别鉴权；公告管理仍用独立管理码
- [x] 新增迁移为已有租户、角色和套餐保留原有读取能力，新租户沿用现有授权模式；不修改历史迁移
- [x] 通知中心、铃铛等已有入口按读取权限展示和请求，切换租户后同步更新
- [x] 验证角色和套餐裁剪都能阻止未授权读取，不削弱成员与租户数据隔离
- [x] 相关后端集成测试与前端组件测试通过，更新受影响夹具和权限目录

## Comments

2026-11-13：实现前核实三个成员读取接口均没有读取权限码，仅有 `announcement:manage`。需求方明确选择“新增三种读取权限码，并同步后端鉴权、授权与套餐配置”，授权此次安全边界变化。工作台卡片由工单 01 实现，本工单承担共享权限及既有入口的配套调整。


## Answer

- `IamPermissions` Manifest 升至 v10，生成目录增加三个读取码。读取码纳入 Plan Feature Pack，IAM 管理权限仍按原有规则豁免套餐。
- 九个待办、通知、公告读取与已读端点经现有 `RoleService` 校验，保留 Membership、收件人及 Tenant/RLS 数据范围；公告发布、管理与撤回仍独立使用管理码。
- IAM 新增 V33、platform 新增 V8，未修改历史迁移。已有自定义角色补读取授权；无角色成员补普通可编辑的 `inbox-reader` 角色；现有 Plan 主数据与 IAM 副本都补读取码。新成员/重新加入通过现有成员邀请路径获得普通读取角色；重复邀请已有成员不会恢复已撤销的角色或权限。
- 通知中心仅呈现和请求授权类别，铃铛仅汇总授权类别；租户切换立即清除旧数据并等待权限就绪。支持 `/notifications?tab=announcements&item=<id>` 打开并展开公告。
- 主链路 E2E 角色夹具与通知页访问桩同步读取码；主链路数据、截图及最终浏览器验收由工单 03 统一交付。

验证（2026-11-13，本地隔离 Testcontainers）：

- TDD：HTTP 无角色读取最初返回 200，新增断言期望 403 失败；鉴权落地后转绿。通知权限/深链组件用例同样先失败后通过。
- `backend/./mvnw -o -pl forge-iam test`：342 项全部通过，包含 V32 旧库升级、已有角色/无角色成员、初始读取角色、角色和 Plan 撤权、租户/成员隔离。
- `backend/./mvnw -o -pl forge-platform test -DforkCount=1`：64 项全部通过，包含已有套餐读取权限迁移。
- `pnpm --filter @forge/tenant-app test`：253 项全部通过；其中通知中心与铃铛 7 项覆盖权限过滤、深链、切租户和旧响应失效。
- 租户端 `typecheck`、全局 `lint:style`、`format:check`、`git diff --check` 通过。
- 权限目录由当前 v10 IAM Manifest 与未改动服务已构建 Manifest 重新生成，确定性比对通过；权限脚本 19 项测试通过。
