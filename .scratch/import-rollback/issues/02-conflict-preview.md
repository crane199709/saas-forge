# 02: 冲突检测与回滚预览

**What to build:** 按 [spec](../spec.md) 的“实现决策”，回滚前检查记录在导入后是否又被修改或被后续导入覆盖，并返回冲突报告。

**Blocked by:** 01

**Status:** resolved

- [x] 冲突判定覆盖：记录 `updated_at`/版本变化、后续导入对同一记录的覆盖
- [x] 预览接口返回冲突记录清单、冲突原因、总数与可回滚数量
- [x] 存在冲突时默认阻断，不自动执行回滚
- [x] 快照过期时明确返回“不可回滚”，不返回空报告
- [x] 测试覆盖：无冲突、被人工修改、被后续导入覆盖、快照过期

## Comments


## Answer

已完成安全只读冲突预览，未实现回滚执行或业务恢复写入。

- SDK 新增 `ImportRollbacks.preview(taskId, model, ImportVersionReader)`，沿用 `IoTasks.get` 当前租户与本人提交任务的访问边界，不扩大到管理员读取他人任务。读取器每次按当前成员 Data Policy 查询数据库修改标记；不存在、已删除或不可见统一返回 `RECORD_UNAVAILABLE`，不泄露旧值或当前字段原文。
- `ImportRollbackPreview` 返回 `taskId`、`available`、`unavailableReason`、`blocked`、`totalCount`、`rollbackableCount`、`conflictCount`、`snapshotExpiresAt`、`conflicts(recordId, reason)`。快照可用但有冲突时 `available=true / blocked=true`，表示默认阻断整单执行；预览从不改写业务数据。
- 稳定原因码：任务不可用为 `TASK_NOT_SUCCEEDED`、`MODEL_MISMATCH`、`NO_SNAPSHOTS`、`SNAPSHOT_EXPIRED`、`SNAPSHOT_UNAVAILABLE`；逐条冲突为 `RECORD_UNAVAILABLE`、`VERSION_UNAVAILABLE`、`RECORD_MODIFIED`、`LATER_IMPORT`。过期明确 `available=false / SNAPSHOT_EXPIRED`，保留任务覆盖总数与实际截止时间，不能把空报告解读为可回滚。
- 后续导入按 `importModel + recordId` 区分业务对象，确认写入器持有业务行锁并完成写入后，在原事务内把已存在的其他同模型同记录快照标记 `superseded_by_task_id`；本任务快照标记清空。只标记已有且已完成覆盖的快照，顺序取实际覆盖，不取任务创建时间或 `CURRENT_TIMESTAMP` 的事务起始时间；即使业务版本字符串相同也识别后续覆盖。与业务写入共同提交或回滚，租户拦截器与 RLS 保持启用。
- SDK 公开 `ImportRollbacks.check(backup, reader)` 供工单 03 复用：执行方先在事务中按当前数据权限锁定业务行，再调用该方法读取最新快照状态、重读业务版本，并在同一事务持锁期间条件恢复。不得把预览结果、旧 `ImportBackup` 对象或跨事务检查结果当成执行许可；重查可识别预览后的人工修改与后续覆盖。
- Demo 新增 `GET /orders/import/{taskId}/rollback-preview`，要求独立 `order:importRollback`，Manifest 升至 9；同步权限目录精确断言与 TestGrants。只有导入权限返回 403，跨租户和其他提交人任务返回 404，现有任务读取约束保留。
- SDK 当前 `schema.sql` 含新增列与中文注释；已有服务新增迁移复制 `upgrade-import-rollback-preview.sql`（demo V16），不修改已执行 V15。业务规范、接入指南、archetype 可选 IO 提示与 CHANGELOG 同步。默认 archetype/sub-app-kit 不使用 IO，不新增可选依赖、表、配置或页面。

验证：

- 使用 TDD 公开接缝（demo HTTP / SDK 公共接口），观察无冲突预览 404→绿；实际覆盖顺序与任务创建顺序相反时先红（`RECORD_MODIFIED` 不能代替 `LATER_IMPORT`），加入事务内实际覆盖标记后转绿。
- 安全行为覆盖人工修改、删除、当前 Data Policy 不可见、独立权限、跨租户和他人任务隔离、未完成/无快照/模型不支持、实际 1ms 保留期过期、同 ID 同版本的跨模型与跨租户隔离，以及持有旧 backup 后重新检查。断言公开报告和 HTTP 业务数据，不直接断言快照表行结构。
- 单模块测试前执行 `./mvnw -o -pl examples/demo-service -am install -DskipTests`。
- `./mvnw -o -pl forge-sdk/forge-sdk-io,examples/demo-service test -Dtest=ArchitectureTest,SchemaCommentTest,FunctionalPermissionTest,DemoManifestExportTest,ObjectStorageTest -Dsurefire.failIfNoSpecifiedTests=false`：SDK 1 + demo 70，共 71 项通过，0 失败/错误/跳过；ObjectStorageTest 的 65 项包含 8 项新增预览/重查测试，使用真实 PostgreSQL、Redis、S3。
- Java/XML/POM Spotless、Checkstyle、SDK/服务架构、表与字段注释、新增升级脚本和 demo V16 一致性、`git diff --check` 均通过。日志：`/private/tmp/import-rollback-02-targeted.log`；TDD 红绿日志为 `/private/tmp/import-rollback-02-preview-{red,green}.log` 与 `/private/tmp/import-rollback-02-order-{red,green}.log`。
- 未单独跑模块全量，由集成验收统一执行；本票没有页面改动。真实浏览器链路由后续工单接续，未宣称通过。
