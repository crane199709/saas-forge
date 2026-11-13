# 03: 回滚执行、保留期与审计

**What to build:** 按 [spec](../spec.md) 的“实现决策”，执行回滚：按记录恢复旧值、支持跳过冲突、失败可重试、快照 90 天过期、写审计。

**Blocked by:** 01, 02

**Status:** resolved

- [x] 回滚只恢复快照中的字段值，不删除该次导入新增的行
- [x] 支持 `skipConflicts`：冲突记录跳过并计入报告，不覆盖
- [x] 按记录执行，记录成功/失败/跳过；重复执行幂等，已恢复的不重复处理
- [x] 快照默认保留 90 天（可配置），过期由清理任务删除；过期后不可回滚
- [x] 独立 Permission Code；审计 `io.importRollback` 记录任务号、恢复数、跳过数、失败数
- [x] 测试覆盖：无冲突回滚、跳过冲突、重试、幂等、过期、权限拒绝、跨租户隔离

## Comments


## Answer

已完成回滚执行、保留期清理、独立审计及 Demo 接入。

- SDK 新增公开 `ImportRollbacks.execute(taskId, model, skipConflicts, ImportRollbackWriter)` 与 `ImportRollbackResult`。写入器提供当前数据权限下的 `readVersion`、事务内 `lockAndReadVersion` 与仅恢复快照字段的 `restore`；Demo 仅恢复标题、金额、客户来源，保留编号、状态、归属及该次导入新增订单，并记录当前 Identity 的 `updated_by`。后台导入通过既有 CurrentActorProvider 继续写系统操作的 null。
- 默认执行重新预检，已有冲突时一条不恢复；只有显式 `skipConflicts=true` 才跳过冲突并继续。预检后才发生的人工修改或后续导入，会在业务行锁、快照锁内重新 `check`；未确认跳过时停止后续恢复，已提交记录保留，不隐式记为跳过。报告只含主键、稳定原因码和计数，不返回快照原文。
- 整次调用持有数据库本人任务行锁，重复/并发请求串行化，连接或事务结束自动释放，不保存永久占用状态。每条恢复与 RESTORED 标记在独立事务共同提交，成功标记须实际更新一行；失败共同回滚并记 FAILED，重试排除已恢复记录，也不会把自身恢复产生的新版本误报为冲突。模拟执行中断后可继续，先前已提交恢复不再执行。
- `IoTask` 原始导入状态不变，追加 `rollbackStatus`（NONE/SUCCEEDED/PARTIAL/BLOCKED）、`rollbackAttempts`、`restoredCount`、`skippedCount`、`failedCount`。次数统计完成的调用；响应和任务展示累计恢复数及当前跳过/失败数。SDK 不可用报告为 UNAVAILABLE，任务保留既有回滚结果。`snapshotAvailable` 在全部恢复后为 false。
- Demo 新增 `POST /orders/import/{taskId}/rollback`，请求 `{"skipConflicts": false}`，沿用 Manifest v9 的独立 `order:importRollback`。成功或逐记录部分完成返回 200 的 SUCCEEDED/PARTIAL 报告；默认冲突返回 409 `io.rollbackConflicts`，过期、无快照、模型不支持等返回 409 `io.rollbackUnavailable`，精确原因/冲突由 GET 预览重新获取。当前租户和本人任务边界保持：只有导入权限为 403，跨租户/他人任务为 404；当前数据权限不可见的记录统一为 RECORD_UNAVAILABLE，不覆盖。
- SDK 写独立 `io.importRollback` 审计，含 taskId、当前 actor/tenant 与本次实际恢复/跳过/失败三项计数；幂等重复调用实际恢复数为 0，不重复计算为新恢复。错误使用 IoErrorCode 的明确 lowerCamelCase 码。
- `forge.io.backup-retention` 默认 90 天可配置。到期先锁任务、汇总逐记录已提交结果，再清空字段旧值并逻辑删除快照；保留任务、快照总数、实际截止时间及回滚历史，过期明确 SNAPSHOT_EXPIRED。进程中断尚未完成汇总的成功计数也会在清理前保留。普通 Tenant Task 仅运行 trial/active，因此同时复用既有 GlobalTask 的已提交租户扫描和基础设施维护豁免，停用租户也会清理，不扩大业务写入豁免。租户删除同样清空旧值并逻辑删除。
- Demo 新增 V17，未改 V15/V16；SDK 当前 schema.sql 与 `upgrade-import-rollback-execution.sql`、业务规范、archetype 可选 IO 提示、接入指南和 CHANGELOG 同步。默认 archetype/sub-app-kit 不使用 IO，不添加可选表、依赖、配置或页面。

验证：

- TDD 按已授权的 demo HTTP / SDK 公开接缝纵向推进，观察过回滚接口 404、到期未清理、锁内新冲突被隐式跳过、并发清理提前完成、操作人为空、过期改写历史状态、当前数据权限不可见误计失败、中断后过期丢计数、停用租户未清理等红灯，均修复并重验。真实恢复失败通过公开业务回调在写入后抛异常注入，不 mock SDK 内部类；中断用公开回调异常模拟，没有宣称执行了操作系统进程终止测试。
- 单模块测试前执行 `./mvnw -o -pl examples/demo-service -am install -DskipTests`，最终安装日志 `/private/tmp/import-rollback-03-install-final.log`。
- 最终完整重跑 `./mvnw -o -pl forge-sdk/forge-sdk-io,examples/demo-service test -Dtest=ArchitectureTest,SchemaCommentTest,FunctionalPermissionTest,DemoManifestExportTest,ObjectStorageTest -Dsurefire.failIfNoSpecifiedTests=false`：SDK 1 + demo 83，共 84 项通过，0 失败/错误/跳过；ObjectStorageTest 78 项包含本票 13 个新增公开行为测试，使用真实 PostgreSQL、Redis、S3。日志 `/private/tmp/import-rollback-03-targeted-final.log`。
- 首次整组回归的两项新增夹具错误已查证修复：覆盖模型要求四列表头；回滚外层事务内完成源对象上传会使其状态未提交给异步校验。改为完整四列文件，并在回滚前通过 HTTP 提交/验证后续任务，预检回调仅确认真实后续覆盖。相关两项独立通过后，最终上述同组整体重验全部通过；首跑证据 `/private/tmp/import-rollback-03-targeted.log`，独立重验 `/private/tmp/import-rollback-03-later-and-expiry-green.log`。
- Checkstyle、SDK/服务架构、中文表/列注释、相关模块及 archetype 的 Spotless、V17 与 SDK 升级脚本一致性、`git diff --check` 通过。格式日志 `/private/tmp/import-rollback-03-format-final.log`。
- 按集成约定，本票未跑模块全量或浏览器；模块全量由集成统一运行，真实浏览器链路由工单 04 验收。没有页面改动。
