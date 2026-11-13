# 04: 文件保留验收与文档

**What to build:** 按 [spec](../spec.md) 完成端到端验收，并更新业务服务接入指南与存储用途说明。

**Blocked by:** 01, 02, 03

**Status:** resolved

- [x] demo-service 声明一个带保留期的用途，覆盖到期、宽限、删除、配额释放
- [x] 验证未实现检查器时跳过并告警；实现后能删除无引用文件
- [x] 验证被逻辑删除业务行引用的文件不会被删除
- [x] 接入指南说明保留期、引用检查、配额释放与 Legal Hold 的现状
- [x] 测试覆盖：端到端生命周期、跳过路径、跨租户隔离
- [x] 不新增前端页面；如需展示到期状态，另立工单

## Comments

## Answer

实现提交：`002a592e920d6f3dc4d4d5aaeeb9e6c8b6f53337`（`codex/file-retention-04`）。demo 的 `order-attachment` 声明 30 天保留期，通过真实业务 HTTP 上传入口、S3、公开 SDK 与治理任务验证孤儿到期失效、七天宽限后删除及配额释放；复核缺检查器、逻辑删除行引用与跨租户隔离的既有覆盖。接入指南与 CHANGELOG 已同步，明确 IO/历史未覆盖用途安全跳过并持续占容量，`HELD` 仅预留；未新增页面。

完整行为矩阵、命令与验证边界见[验收记录](../acceptance.md)，交接见 `/tmp/saas-forge-file-retention/04-handoff.md`。测试证据均在 `/tmp/saas-forge-file-retention/`：

- `04-demo-retention-red.log` / `04-demo-retention-green.log`：新增行为先以截止时间为空产生预期失败，补生产声明后 1 项通过。
- `04-final-install.log` / `04-full-verify.log`：当前 SDK 安装成功；storage 11 项、IO 1 项、demo 162 项，共 174 项通过，0 失败/错误/跳过，含 ObjectStorageTest 96 项及 Spotless。
- `04-archetype-generator.log` / `04-sub-app-generator.log`：生成工程 17 项测试及后置规范脚本、前端生成器 2 项测试通过。
- `04-docs-format-final.log` / `04-docs-baseline-format.log`：新增验收文档、CHANGELOG 与变更指南段格式检查通过；整份指南存在基线格式差异，未宣称整文通过。

本次集成不重跑测试；验收使用隔离容器和可控时钟，未验证真实供应商部署、IAM/RocketMQ 跨服务联调或生产迁移。整个规格的最终关闭仍由集成审查决定。
