# 01: Purpose 保留期与对象生命周期

**What to build:** 按 [spec](../spec.md) 的“实现决策”，Storage Purpose 增加可选保留期，`forge_storage_object` 增加生命周期状态与保留截止时间。

**Blocked by:** —

**Status:** resolved

- [x] `StoragePurpose` 增加可选 `retentionDays`，大于零；未声明表示永久保留
- [x] 启动时校验保留期，非法值直接失败
- [x] 文件表增加状态、保留截止时间与失效时间；状态至少覆盖 `STORED`、`EXPIRED`、`PURGE_PENDING`、`DELETED`，枚举可扩展
- [x] 到期只标记失效，不删除对象；宽限期固定 7 天
- [x] 既有文件迁移为 `STORED`，未声明保留期的用途不受影响
- [x] 测试覆盖：保留期校验、到期标记、宽限期计算、既有数据兼容

## Comments

## Answer

- 实现提交：`5697fa654a0a9bd38094666fe4ecfaa02c73785f`（`codex/file-retention-01`）。`StoragePurpose` 保持四参永久保留兼容，新增正整数 `retentionDays`；文件登记增加生命周期枚举、保留截止与实际失效时间。新增生命周期读取、到期失效、宽限期内恢复与延长接口；删除只标记失效，七天内保留 S3 对象及配额，失效后拒绝读取与新下载签名。
- 新增 SDK 升级脚本与 demo V18，不改历史迁移，原 `STORED`/`PENDING` 状态不变且截止时间为空；规范、接入指南、脚手架提示与 CHANGELOG 同步。引用检查与物理清理由后续票 03 接续，接口与并发约束见 `/tmp/saas-forge-file-retention/01-handoff.md`。
- 验证证据：`/tmp/saas-forge-file-retention/01-storage-targeted.log` 的用途及架构测试 4 项通过；`01-demo-targeted.log` 的 15 项中 14 项通过，单项认证初始化修正后在 `01-io-result-rerun.log` 通过，15 项均已有通过记录；配额拒绝回归见 `01-quota-reject-green.log`（1 项通过）。
- 脚手架生成测试见 `/tmp/saas-forge-file-retention/01-sub-app-generator.log`（2 项通过）；storage、demo、archetype 格式检查见 `01-spotless-check.log`（BUILD SUCCESS）。本次合并复核非空 diff 并执行 `git diff --check`；完整相关模块测试留至集成最终验收。
