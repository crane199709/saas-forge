# 01: 覆盖前快照

**What to build:** 按 [spec](../spec.md) 的“实现决策”，`forge-sdk-io` 为覆盖导入增加快照表，并在确认写入前保存将被覆盖的字段旧值。

**Blocked by:** —

**Status:** resolved

- [x] SDK 提供快照表迁移，带标准列与租户隔离，并接入租户清理
- [x] 覆盖导入在确认写入前创建快照，保存记录主键与本次导入会覆盖的字段旧值
- [x] 不保存新增行，不保存未受导入影响的字段
- [x] 快照与任务关联；任务记录能查到是否有可用快照
- [x] 测试覆盖：覆盖导入生成快照、追加导入不生成、跨租户隔离、租户删除清理

## Comments


## Answer

已完成覆盖前快照及任务可用性元数据，未实现回滚执行、冲突报告或界面。

- SDK 新增 `ImportWriter<T>`：`beforeWrite` 在确认事务内锁定旧记录并返回 `ImportSnapshot(recordId, values)`，新增行返回 null；SDK 先持久化旧值再调用 `write`，后者返回数据库的写后修改标记。快照与业务写入共同提交/回滚；相同任务重复覆盖同一记录时保留最初旧值和最终修改标记。值允许 null，高精度数值读取保留为 BigDecimal。
- `ImportBackups.list(taskId, size, offset)` 是后续服务内修复的公开扩展点（size 1–1000），按当前租户隔离，不通过通用 HTTP 接口暴露旧字段原文。`ImportBackup` 提供快照 ID、任务 ID、记录主键、旧字段值、写后修改标记与实际截止时间。
- `IoTask` 新增 `snapshotCount`、`snapshotExpiresAt`、`snapshotAvailable`。追加与纯新增没有快照或截止时间；成功且有未过期快照时才可用。`forge.io.backup-retention` 默认 90 天。租户删除事务逻辑删除本租户快照并清空任务可用性元数据，重复投递安全，其他租户不受影响。到期清理由后续工单接续。
- SDK 自动持久化 `importModel`（record 全限定类名），确认请求同步核对模型，不匹配返回 `400 / io.invalidFile` 且任务继续待确认。历史 null 值兼容原确认流程；后续冲突判定按 `importModel + recordId` 区分不同业务对象。
- Demo 保留原三列追加模型/模板及确认接口；新四列覆盖模板为标题、金额、客户来源、可选订单ID。空 ID 新增，有 ID 只覆盖标题、金额、客户来源（空来源写 null），不改编号/状态/归属。路径：`GET /orders/import/overwrite/template`、`POST /orders/import/overwrite`、`POST /orders/import/overwrite/{taskId}/confirm`，上传继续复用 `/orders/import/upload`。历史界面按 `importModel === "io.saasforge.demo.order.model.OrderOverwriteRow"` 选择覆盖确认路由，其余/历史 null 选择原路由。
- SDK `schema.sql` 已包含新表、RLS、标准列与中文注释；已有 IO 服务新增迁移复制 `upgrade-import-backup.sql`（demo V15），不得改已执行迁移。规范、指南、依赖门禁与 CHANGELOG 已同步。默认 archetype/sub-app-kit 不使用 IO，未额外加入表或依赖；archetype 注释标明可选迁移接入，规范仍由构建复制。

验证：

- TDD 纵向切片已观察红→绿：覆盖路径缺失 404；持久模型缺失；零快照误带截止时间；租户清理后可用性残留；泛型 JSON 数值精度丢失，均已修复并重验。
- 先执行 `./mvnw -o -pl examples/demo-service -am install -DskipTests`，保证测试消费当前 SDK。
- `./mvnw -o -pl forge-sdk/forge-sdk-io,examples/demo-service test -Dtest=ArchitectureTest,SchemaCommentTest,ObjectStorageTest -Dsurefire.failIfNoSpecifiedTests=false`：IO 1 + demo 59，共 60 项通过，0 失败/错误/跳过；包含真实 PostgreSQL、Redis、S3 与 6 个新增行为测试。
- 本票相关 Java/XML/POM Spotless、Checkstyle、架构规则、表/列注释、迁移脚本一致性与 `git diff --check` 均通过。
- 日志：`/private/tmp/import-rollback-01-targeted.log`；各纵向切片的 red/green 日志为 `/private/tmp/import-rollback-01-*.log`。按集成约定，本票未单独跑模块全量，最终由集成验收统一执行；本票没有页面改动，浏览器验收随后续 UI 工单执行。
