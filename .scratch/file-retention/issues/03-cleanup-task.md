# 03: 清理任务、配额与审计

**What to build:** 按 [spec](../spec.md) 的“实现决策”，清理任务按用途处理到期文件：检查引用、标记失效、宽限期后删除对象并释放配额。

**Blocked by:** 01, 02

**Status:** resolved

- [x] 清理任务在各业务服务内按 Tenant 运行，按用途分批处理
- [x] 单个用途失败不影响其他用途；失败原因可观测
- [x] 宽限期结束后删除 S3 对象与文件记录；删除失败保留记录并告警，不释放配额
- [x] 物理删除时释放配额；宽限期内仍计入已用容量
- [x] 审计 `storage.expire`、`storage.purge`、`storage.retentionExtend`；不记录文件内容
- [x] 测试覆盖：完整生命周期、引用跳过、异常跳过、配额变化、审计事件

## Comments

## Answer

- 实现提交：`582ade02a9c9b9464bdbadfb5a4c7018631a179a`（`codex/file-retention-03`），合入 `codex/file-retention-integration`。
- `StorageMaintenance.clean()` 通过 `forge-storage-retention` 租户任务每小时运行，从本租户对象登记枚举用途，按 UUID 游标分批处理。到期与清除各自重新检查引用；缺少覆盖、检查异常及单对象失败均安全跳过，继续处理其余批次和用途，日志只包含安全上下文。
- 到期只失效，七天宽限内保留对象、记录与配额；清除与恢复、延长共用对象行锁，锁内复核状态、截止时间和租户/服务 key，上传 URL 仍有效时继续保留。S3 删除失败保留 `PURGE_PENDING`、登记和配额；仅 S3 成功后移除记录并释放配额，数据库回滚后的重试通过幂等删除最终完成清除。
- 补齐 `storage.expire`、`storage.purge`、`storage.retentionExtend` 审计，详情不包含文件内容、文件名或凭证；沿用提交后独立写入审计 Outbox 的既有保证，外层事务回滚不记录成功。同步 storage 的 audit/scheduling 依赖、依赖门禁、业务规范、接入指南、脚手架提示和 CHANGELOG。
- 测试证据：`/tmp/saas-forge-file-retention/03-lifecycle-{red,green}.log` 和 `03-audit-{red,green}.log` 记录入口缺失/审计断言失败到通过；其余 `03-{s3-retry,batch-references,purpose-isolation,purge-concurrency,expiry-concurrency,pending-cleanup,key-upload-boundaries,db-retry-deleted-reference}.log` 均通过。合计 10 项新增真实集成行为测试及 1 项扩展既有测试，覆盖生命周期、引用与异常跳过、配额、审计、并发、上传凭证和失败重试。
- 验证证据：`03-final-install.log` 的依赖安装成功；`03-architecture.log` 中 storage 架构测试 1 项及 demo 架构/字段规范测试 5 项通过；`03-spotless-check.log` 中 storage/demo/archetype 检查通过；`03-sub-app-generator.log` 中生成器测试 2 项通过。测试使用隔离 PG/Redis/S3 容器；合并阶段仅核对既有证据和 Git 差异，不重复执行测试，全模块验收由票 04 统一执行。
