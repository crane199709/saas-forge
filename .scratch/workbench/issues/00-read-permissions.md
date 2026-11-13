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
- IAM 新增 V33、platform 新增 V8，未修改 V1–V32 等历史迁移。已有自定义角色补读取授权；无角色成员补普通可编辑读取角色，由 IAM 内部 `tenant_initial_read_role` 授权关联定位。默认编码 `inbox-reader` 已被自定义角色占用时，为系统创建的读取角色另用 UUID 后缀编码，保留原角色及其成员、授权。现有 Plan 主数据与 IAM 副本都补读取码。新成员/重新加入只复用 IAM 指定的初始读取角色；重复邀请已有成员不会恢复已撤销的角色或权限。
- 通知中心仅呈现和请求授权类别，铃铛仅汇总授权类别；租户切换立即清除旧数据并等待权限就绪。支持 `/notifications?tab=announcements&item=<id>` 打开并展开公告。
- 主链路 E2E 角色夹具与通知页访问桩同步读取码；主链路数据、截图及最终浏览器验收由工单 03 统一交付。

验证（2026-11-13，本地隔离 Testcontainers）：

- TDD：HTTP 无角色读取最初返回 200，新增断言期望 403 失败；鉴权落地后转绿。通知权限/深链组件用例同样先失败后通过。
- `backend/./mvnw -o -pl forge-iam test`：342 项全部通过，包含 V32 旧库升级、已有角色/无角色成员、初始读取角色、角色和 Plan 撤权、租户/成员隔离。
- `backend/./mvnw -o -pl forge-platform test -DforkCount=1`：64 项全部通过，包含已有套餐读取权限迁移。
- `pnpm --filter @forge/tenant-app test`：253 项全部通过；其中通知中心与铃铛 7 项覆盖权限过滤、深链、切租户和旧响应失效。
- 租户端 `typecheck`、全局 `lint:style`、`format:check`、`git diff --check` 通过。
- 权限目录由当前 v10 IAM Manifest 与未改动服务已构建 Manifest 重新生成，确定性比对通过；权限脚本 19 项测试通过。

### 2026-11-13 最终审查修复

- Standards P3：`InboxReadPermissionTest` 的成员与角色服务改为显式导入加简单类名，符合通用规范的类型引用要求。
- Spec P1：消除升级迁移和新邀请按可自建的 `inbox-reader` 编码复用角色的越权路径。未发布的 V33 改为先保存新角色 ID、创建隔离读取角色，再关联无角色成员；运行时通过内部关联定位同租户未删除角色。关联表沿用 IAM `role` 全局授权表的隔离约定，不暴露公共接口，不引入工作台业务数据表。
- 保留管理员对系统创建角色的有意修改：新邀请不补回已撤销的读取权限；读取角色删除后重新创建并更新内部关联，绝不借用其后新建的同编码管理角色；既有成员已撤销的角色也不因新成员加入而恢复。并发首次邀请仍经现有租户角色事务锁，只创建一个初始读取角色。
- TDD RED：旧库先停在 V32，预置带 `iam:role:manage` 的同编码自定义角色和既有成员；升级后无角色成员意外获得该管理权限，公开服务断言失败。新邀请冲突回归同样实际获得管理权限且缺少三种读取码，断言失败。日志 `/tmp/workbench-review-migration-privilege-red.log`、`/tmp/workbench-review-collision-red.log`。
- GREEN：修复后迁移、邀请、撤权、删除后冲突、并发邀请及既有通知收件箱测试共 17 项通过，日志 `/tmp/workbench-review-affected-green.log`。
- 最终 `backend/./mvnw -o -pl forge-iam spotless:check test`：346 项全部通过（0 失败、0 错误、0 跳过，1m37s）；Spotless、Checkstyle、Schema 注释与架构检查通过，日志 `/tmp/workbench-review-iam-full.log`。`git diff --check` 通过。此次未修改 platform、前端与浏览器代码，既有验收边界仍见工单 03。

审查记录见 [review.md](../review.md)。V33 仅在未发布功能分支和本次临时测试库中使用；未操作开发、共享或生产数据库。
