# 01: ai-platform 仓库与 CI 骨架

**What to build:** 按 [spec](../spec.md) 的“实现决策”，建立独立 `ai-platform` 仓库与三套构建：Java 服务、Node runner、Vue Sub-App。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] 仓库包含 `service/`、`runner/`、`sub-app/`、`deploy/`、`docs/`
- [ ] Java 服务可构建、可测试；Node runner 可构建、可测试；Sub-App 可构建
- [ ] CI 分别运行三套检查，并有统一的集成构建入口
- [ ] `docs/GLOSSARY.md` 迁入 design.md 的 AI 术语
- [ ] `docs/adr/` 建立，先落 6 个 ADR 候选
- [ ] 明确 SDK 依赖方式（发布快照）与每日集成构建
- [ ] README 说明本地启动、测试与部署前提

## Comments
