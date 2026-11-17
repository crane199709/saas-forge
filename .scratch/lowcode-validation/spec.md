# 低代码业务验证：平台反馈

Status: resolved

以仓库外工程 `~/workspace/coding/sub/lowcode`（`lowcode-service` + `lowcode-sub-app`，由 `forge-service-archetype` 与 `sub-app-kit` 0.1.0 生成）实现低代码一期（数据源、数据集、大屏），验证脚手架、SDK 与接入指南能否支撑真实规模的业务。业务规格与领域术语在该工程内（`.scratch/lowcode-v1/spec.md`、`GLOSSARY.md`、`docs/adr/`），本目录只记录平台需要改什么。

每个工单写明现象、证据、复现方式与期望；业务工程中的临时绕行同时写在工单里，平台修复后由业务工程撤掉。

## Comments

### 2026-11-17 状态收口

工单 01—06 均已标记 `resolved`。按用户本次确认，将本规格同步关闭；平台修复与验收记录以各工单为准，本次仅同步文档状态。

发布包含脚手架规范的正式版本 `v0.2.0` 仍由维护者安排，继续保留在[工单 01 的余项记录](issues/01-release-version-drift.md#comments)中；本次规格关闭不表示已完成正式发布或撤除业务工程的临时包绕行。
