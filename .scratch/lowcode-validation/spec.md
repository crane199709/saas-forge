# 低代码业务验证：平台反馈

Status: needs-triage

以仓库外工程 `~/workspace/coding/sub/lowcode`（`lowcode-service` + `lowcode-sub-app`，由 `forge-service-archetype` 与 `sub-app-kit` 0.1.0 生成）实现低代码一期（数据源、数据集、大屏），验证脚手架、SDK 与接入指南能否支撑真实规模的业务。业务规格与领域术语在该工程内（`.scratch/lowcode-v1/spec.md`、`GLOSSARY.md`、`docs/adr/`），本目录只记录平台需要改什么。

每个工单写明现象、证据、复现方式与期望；业务工程中的临时绕行同时写在工单里，平台修复后由业务工程撤掉。
