# 工作台最终审查修复

修复基线：`811153c358435d2a68fb9032b2f8582133fee8ee`（`codex/workbench-integration`）。规格见 [spec.md](spec.md)，权限工单见 [00-read-permissions.md](issues/00-read-permissions.md)。本记录分别保留 Standards 与 Spec 的发现和修复，不把两个维度混排。

## Standards

- **P3：测试服务类型使用不必要的全限定名。** `InboxReadPermissionTest` 的 `MembershipService`、`RoleService` 没有同名冲突，却以全限定名声明字段，违反 `docs/standards/business-service.md`「类型引用」中生产与测试代码使用显式导入加简单类名的要求。
- **修复：** 增加两个显式 `import`，字段使用简单类名。没有为纯导入调整增加冗余测试；新增 Mapper 方法均有文档注释，新增纯关联表及全部列均有中文 `COMMENT ON`。
- **验证：** IAM `spotless:check`、Checkstyle 已通过，最终全量结果见下文。

Standards：1 项发现已修复，最高 P3；未解决 0 项。

## Spec

- **P1：按未保留的角色编码复用默认读取角色，会传播已有自定义角色的管理权限。** V33 和 `RoleService.grantInitialReadRole` 原本以 `code = 'inbox-reader'` 识别初始角色；`RoleManagementService.create` 仅保留 `owner`、`tenant-admin`，租户早已可能自建 `inbox-reader` 并授予管理或业务权限。升级时无角色成员、新邀请成员都会误获这些原有权限。这违背规格中「通过新增迁移兼容已有租户、角色和套餐的读取能力」及沿用现有角色/套餐撤权机制的要求。
- **修复：** role 功能内部新增两列的 `tenant_initial_read_role` 纯授权关联表，以系统创建时保存的角色 ID 识别初始角色。V33 遇到旧编码冲突时新建带 UUID 后缀的读取角色，只向无角色成员分配它；已有自定义角色继续补原规格要求的读取码，原有成员与其他权限保持。运行时先取可信关联；不存在或指向已删除角色时创建隔离角色，编码冲突只换编码，不继承原角色的任何权限。查询和写入显式约束同租户及未删除角色，沿用 IAM `role` 的全局授权表隔离约定；不跨功能包访问 Mapper，不暴露新的公共接口。
- **管理员修改语义：** 系统创建的角色仍是普通可编辑角色。之后对它的权限修改属于管理员的有意授权；未来邀请沿用当前授权，不补回已撤销权限。成员已撤销的分配不因重复邀请恢复。删除该角色后的新邀请另建并重指初始角色，不恢复旧成员分配。并发首次邀请继续由现有租户角色事务锁串行化。
- **RED 证据：** 修复前的公开服务集成回归中，V32 旧库预置的管理角色在升级后让无角色成员多出 `iam:role:manage`（`/tmp/workbench-review-migration-privilege-red.log`）；首次邀请回归实际得到 `iam:role:manage`，缺少三种读取码（`/tmp/workbench-review-collision-red.log`）。最初 5 项测试中恰有这两条冲突回归失败。
- **GREEN 证据：** 修复后 `InboxReadMigrationTest`、`InboxReadPermissionTest`、`NotificationInboxTest` 共 17 项通过（`/tmp/workbench-review-affected-green.log`），涵盖旧库碰撞、迁移后邀请、首次邀请碰撞、撤权后新邀请、删除后同码碰撞、并发邀请，以及原有重复邀请和成员状态/收件箱回归。

Spec：1 项发现已修复，最高 P1；未解决 0 项。

## 最终验证与边界

- 按仓库要求先执行 `backend/./mvnw -o -pl forge-iam -am install -DskipTests`，成功；日志 `/tmp/workbench-review-prep.log`。
- 最终 `backend/./mvnw -o -pl forge-iam spotless:check test`：346 项全部通过，0 失败、0 错误、0 跳过，1m37s；Spotless、Checkstyle、Schema 注释与架构检查通过，日志 `/tmp/workbench-review-iam-full.log`。`git diff --check` 通过。
- V33 尚未发布，仅在本次临时 Testcontainers 库使用，允许在本变更内修订；未修改 V1–V32，未操作开发、共享或生产数据库。新增关联属于 IAM 授权关系，不是工作台业务数据表，也不是 SDK 必需表。
- 此次未修改 platform 或前端，不重复运行此前通过的相应模块和浏览器套件。完整视觉回归的 4 项既有字典基线差异及 1 项已定向通过的波动，仍按 [工单 03](issues/03-e2e-and-visual.md) 保留，不在本次修复范围。

合计：Standards 1 项（P3）已修复；Spec 1 项（P1）已修复。

## 集成验收（2026-11-13）

独立全新上下文复核了修复提交 `c469a596e1fa5c2d35fa3f4477596d22e277f17b`，确认迁移和邀请均不再按角色编码复用已有自定义角色；同租户约束、撤权、删除重建和并发路径未发现遗留问题，导入规范问题也已修复。修复已合入 `codex/workbench-integration`（合并提交 `6e63af96b0b0e820f997ef0f6b34160533d4a741`）。四张工单均为 `resolved`，规格同步关闭。

最终验证：IAM 346/346、platform 64/64、权限脚本 19/19、租户组件 255/255、真实主链路 5/5、通用 E2E 65/65、定向视觉 21/21，通过相应类型、样式与格式检查。完整视觉 201/206 的既有差异与复验结论见工单 03，不声称全量视觉全部通过。

临时工作树清理前，六份隔离 E2E 服务日志另存 `/tmp/workbench-qa/service-logs/`，没有复制凭证和签名文件。交付截图另存当前聊天的 `/Users/liuhuan/.codex/visualizations/2026/11/13/01a1d3f3-23ae-73a3-959d-fe0871f5f7a8/`（文件名以 `workbench-` 开头），可在工作树归档后继续查看。
