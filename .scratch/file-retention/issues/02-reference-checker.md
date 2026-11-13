# 02: 引用检查 SPI

**What to build:** 按 [spec](../spec.md) 的“实现决策”，SDK 提供 `FileReferenceChecker` SPI，业务服务用自己的库批量返回仍被引用的对象 ID。

**Blocked by:** 01

**Status:** resolved

- [x] SPI 接收候选对象 ID 列表，返回仍被引用的 ID；调用发生在当前租户上下文
- [x] 逻辑删除的业务行仍算引用
- [x] 未实现检查器、检查异常或超时：跳过删除并告警，不按“无引用”处理
- [x] 提供批量接口，避免逐对象查询；单次批量大小可配置
- [x] public API 带文档注释；接入指南说明如何实现
- [x] 测试覆盖：有引用、无引用、未实现、抛异常、超时、逻辑删除行

## Comments

## Answer

- 实现摘要：新增按用途声明覆盖范围的 `FileReferenceChecker` SPI 与 `FileReferenceChecks`，在当前租户上下文批量合并引用；缺覆盖、异常、超时及无效返回均告警并安全跳过。批量上限与超时可配置，工作线程有界。demo 的 `order-attachment` 检查包含逻辑删除附件，保持租户隔离；其他未覆盖用途不放行。公开 API、接入指南、规范及脚手架已同步。
- 实现提交：`9bec622c620e40c32595dda9d103f9ae7e2ef500`（`codex/file-retention-02`）；交接：[02-handoff.md](/tmp/saas-forge-file-retention/02-handoff.md)。
- 测试证据：storage 定向测试 11 项通过，见 [02-storage-targeted.log](/tmp/saas-forge-file-retention/02-storage-targeted.log)；demo 定向测试 6 项通过（含真实 S3/PostgreSQL/HTTP、逻辑删除引用与租户边界），见 [02-demo-targeted.log](/tmp/saas-forge-file-retention/02-demo-targeted.log)；sub-app-kit 生成器 2 项通过，见 [02-sub-app-generator.log](/tmp/saas-forge-file-retention/02-sub-app-generator.log)；Spotless 检查通过，见 [02-spotless-check.log](/tmp/saas-forge-file-retention/02-spotless-check.log)。各轮 red/green 证据索引见交接文件；合并时核验已有日志，不重跑测试。
