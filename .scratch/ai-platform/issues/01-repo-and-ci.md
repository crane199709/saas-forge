# 01: ai-platform 仓库与 CI 骨架

**What to build:** 按 [spec](../spec.md) 的“实现决策”，建立独立 `ai-platform` 仓库与三套构建：Java 服务、Node runner、Vue Sub-App。

**Blocked by:** —

**Status:** ready-for-agent

- [x] 仓库包含 `ai-service/`、`runner/`、`ai-sub-app/`、`deploy/`、`docs/`
- [x] Java 服务可构建、可测试；Node runner 可构建、可测试；Sub-App 可构建
- [x] CI 分别运行三套检查，并有统一的集成构建入口
- [x] `docs/GLOSSARY.md` 迁入 design.md 的 AI 术语
- [x] `docs/adr/` 建立，先落 6 个 ADR 候选
- [x] 明确 SDK 依赖方式（发布快照）与每日集成构建
- [x] README 说明本地启动、测试与部署前提

## Comments

### 命名约定（2026-11-14 用户补充）

参考 lowcode：后端目录与制品 ai-service，Java 根包 io.saasforge.ai；前端目录 ai-sub-app，npm 包名 @forge/ai-sub-app。Sub-App 编码仍为 ai，Runner 目录保持 runner。

### 2026-11-14 验收完成

- 后端和前端分别由 Forge 0.2.0-SNAPSHOT archetype、sub-app-kit 生成，移除订单示例；按用户命名要求使用 ai-service / ai-sub-app。Runner 是独立 Node24/TS 工程，仅实现存活入口。
- 本地统一入口 `AI_MAVEN_OFFLINE=true bash scripts/check.sh` 通过：Java5项测试、Runner1项HTTP测试、Vue类型/隔离/样式/构建与2档Playwright截图回归；截图逐张查看。
- 规范审查发现的显式import问题已修复；规格审查无实现偏差。
- [远端CI首轮验收](https://github.com/crane199709/ai-platform/actions/runs/43558951586) 的 service、runner、sub-app 三个job全部通过。service使用隔离Forge缓存，从GitHub Maven Packages下载0.2.0-SNAPSHOT后完成verify与Manifest动作策略检查，没有从平台源码构建SDK。
- 每日北京时间10:45运行同一工作流；统一入口、SDK与前端包固定方式见docs/ci.md。8个术语迁入docs/GLOSSARY.md，6个正式ADR、双语README及部署前提已提交。
- 本工单只交付骨架；真实IAM/网关/审计接入、DSH运行时与聊天能力分别继续按02、03、05等工单实施。

执行仓库：https://github.com/crane199709/ai-platform/blob/main/.scratch/ai-platform/issues/01-repo-and-ci.md；本文件保留平台交接与完成记录，后续工单02—12以AI仓库为准。
