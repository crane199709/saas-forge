# 02: 脱敏引擎与策略库

**What to build:** 按 [spec](../spec.md) 的“实现决策”，在 SDK 提供固定策略库与统一脱敏引擎，供导出、日志、在线三条通道共用。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 策略库支持 `mask_middle`、`mask_tail4`、`keepFirst(n)`、`replace`、`email`、`phone`、`idcard`、`bankcard`、`name`
- [ ] 引擎提供校验与执行两个入口；非空值执行后未改变时明确失败，不把未生效当成脱敏成功
- [ ] 不支持表达式或正则；未知策略、非法参数在保存或启动时失败
- [ ] 预览入口对样例值执行同一引擎，返回脱敏结果与失败原因
- [ ] public API 带文档注释，按 semver 管理
- [ ] 测试覆盖：每种策略、空值、短值、未改变失败、未知策略

## Comments
