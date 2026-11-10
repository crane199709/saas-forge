# 01: SDK 状态流转与确认时重新校验

**What to build:** 按 spec 第 2、3 节，修改 `ExcelIo` 和 `IoTasks` 的导入流程。

**Blocked by:** storage-purpose/02

**Status:** resolved

- [x] 校验通过进入待确认并记录行数，此时不写业务数据
- [x] `confirmImport` 由条件更新保证只成功一次，只有提交人可确认
- [x] 确认请求落到其他实例或重启后仍能完成
- [x] 确认时重新校验，失败生成新的 Error Report 且一行不写
- [x] 待确认任务不被判定为中断、不占队列，24 小时后过期并清理源文件
- [x] 更新接入指南与 `CHANGELOG.md`

## Comments

## Answer

已实现。`ExcelIo.importFile(model, source, validate)` 只校验并转为 `AWAITING_CONFIRM`，保存行数和源文件，释放本轮执行容量。`confirmImport(taskId, model, validate, writer)` 以当前请求上下文确认；数据库按提交人、状态与 24 小时有效期条件抢占，再从持久源文件完整重新校验并事务写入。无需保存模型、回调或本机转换结果，提交实例停止后可由另一实例继续。

同租户非提交人返回 `io.confirmForbidden`，跨租户返回 `io.notFound`；重复、非待确认或过期返回 `io.taskNotAwaitingConfirmation`。队列满或实例已关闭时保持待确认，可稍后重试。过期与确认使用互斥的状态条件更新，先抢占再访问源文件；Global Task 将超时任务标为 `FAILED` / `io.confirmationExpired`，复用源清理流程。结束本轮执行时按 progress 条件移除心跳，避免误删刚确认入队的新执行。

接入指南、CHANGELOG 与 SDK 文档已同步；删除旧四参 `importFile`，迁移 demo-service 与现有测试调用。复用现有任务表（状态长度 16），没有新增表、列或迁移。

验证（2026-11-10）：

- 逐切片先红后绿：原实现立即写入（预期 `AWAITING_CONFIRM`，实际 `SUCCEEDED`）；确认 API 不存在；过期任务未标失败；旧四参 API 无法满足新三参调用。随后逐项实现并重验。
- 先执行 `./mvnw -o -pl examples/demo-service -am install -DskipTests`：通过。
- 真实 PostgreSQL、Redis、S3 的 `ObjectStorageTest` 11 项定向测试通过，0 失败、0 跳过：待确认零写入与源保留，提交实例关闭后的跨实例并发单次确认，完整重校验与新错误报告零写入，同租户非提交人/跨租户，队列满重试与不判中断，24 小时过期与真实 S3 源删除，关闭实例重试，行数超限、事务回滚、字典转换及错误报告修正重传、数值/日期校验。
- `./mvnw -o -pl forge-sdk/forge-sdk-io,examples/demo-service test -Dtest=ArchitectureTest`：两个架构测试通过。
- `git diff --check` 通过；Maven 过程中的 Spotless 检查通过。

模块全量与浏览器验收留给集成分支最终验证；本票不提供 Business Service HTTP 确认入口，工单 02 负责。
