# 01: Purpose 保留期与对象生命周期

**What to build:** 按 [spec](../spec.md) 的“实现决策”，Storage Purpose 增加可选保留期，`forge_storage_object` 增加生命周期状态与保留截止时间。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] `StoragePurpose` 增加可选 `retentionDays`，大于零；未声明表示永久保留
- [ ] 启动时校验保留期，非法值直接失败
- [ ] 文件表增加状态、保留截止时间与失效时间；状态至少覆盖 `STORED`、`EXPIRED`、`PURGE_PENDING`、`DELETED`，枚举可扩展
- [ ] 到期只标记失效，不删除对象；宽限期固定 7 天
- [ ] 既有文件迁移为 `STORED`，未声明保留期的用途不受影响
- [ ] 测试覆盖：保留期校验、到期标记、宽限期计算、既有数据兼容

## Comments
