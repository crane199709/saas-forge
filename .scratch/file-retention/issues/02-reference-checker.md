# 02: 引用检查 SPI

**What to build:** 按 [spec](../spec.md) 的“实现决策”，SDK 提供 `FileReferenceChecker` SPI，业务服务用自己的库批量返回仍被引用的对象 ID。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] SPI 接收候选对象 ID 列表，返回仍被引用的 ID；调用发生在当前租户上下文
- [ ] 逻辑删除的业务行仍算引用
- [ ] 未实现检查器、检查异常或超时：跳过删除并告警，不按“无引用”处理
- [ ] 提供批量接口，避免逐对象查询；单次批量大小可配置
- [ ] public API 带文档注释；接入指南说明如何实现
- [ ] 测试覆盖：有引用、无引用、未实现、抛异常、超时、逻辑删除行

## Comments
