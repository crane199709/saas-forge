# 贡献指南

感谢你对 saas-forge 的关注。

## 开始前

- 先从 [领域上下文入口](GLOSSARY-MAP.md)确认术语和所有权，再阅读 docs/ 中与改动相关的产品、架构与安全约束。
- 功能需求、缺陷与 PRD 使用 GitHub Issues 跟踪。
- 与领域术语或关键架构决策相关的改动，应先确认其与现有领域文档和 ADR 一致。

## 本地开发

先按[原生本地开发总入口](docs/native-local-development.md)准备环境，再用应用目录的 `pnpm run dev` 和 IDE Run/Debug 控制应用。完整 Compose 与 replace/restore 工具用于集成验收。

## 提交改动

1. 保持改动聚焦，不在同一变更中混入无关重构。
2. 按[本地分层验证](docs/local-verification.md)执行受影响模块及必要集成检查；认证、契约、迁移等变更扩大对应范围。完整门禁由 CI 承担，本机保留复现能力，专项 Issue 要求继续有效。
3. 通过 Pull Request 合并到 `master`（本仓库默认分支），并说明验证结果与仍存在的限制。

## 行为准则

参与本项目即表示同意遵守 CODE_OF_CONDUCT.md。
