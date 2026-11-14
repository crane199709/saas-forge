# 00: 平台前置（saas-forge）

**What to build:** 按 [spec](../spec.md) 的“实现决策”，在 saas-forge 交付 AI 服务接入所需的通用能力：Plan token 配额、AI 审计动作、权限动作词与 AI Feature Pack。

**Blocked by:** —

**Status:** ready-for-agent

- [x] Plan 增加 `tokenQuota`（nullable，NULL 表示不限）、校验与迁移，`COMMENT ON` 写中文描述
- [x] `TenantPlanChanged` 增加 token 配额字段，随事件下发；现有订阅方保持兼容
- [x] 平台侧 Plan API 与运营端套餐页支持配置 token 配额，并展示
- [x] 确认 AI 审计动作分类与命名（会话、工具、模型、配置变更），写入审计文档
- [x] 权限动作词白名单补充 `use` 等 AI 动作，并纳入 Manifest/目录校验
- [x] AI Feature Pack 可通过 Plan 配置（Sub-App 编码 `ai` 与 AI 权限码）
- [ ] 发布 SDK 快照，供 `ai-platform` 依赖；每日集成构建验证兼容

## Comments

### 2026-11-14 实现与验收

- Plan 使用 `Long` / `BIGINT` 存储每月 Token 总量，正整数或 NULL；新增 platform V9 迁移，历史套餐保持不限。HTTP 集成覆盖创建、列表、修改、清空、非法配额与广播事件。
- `TenantPlanChanged` 保留旧八参构造器；历史 JSON 缺少新字段时不限，新消息仍可由旧结构消费。真实 IAM 集成验证 AI Manifest、Plan 权限与 Sub-App 的启用/撤销。
- 审计动作见 `docs/audit.md`；动作策略新增 `use`、`publish`、`rollback`，目录对导出的 Manifest 校验；SDK/IAM 保持原有运行时编码兼容。规范、脚手架提示与接入指南同步。
- 套餐页支持配置与展示，单测与 1440/1024 浏览器保存操作通过；新增配额截图已查看。为让既有存储容量值通过原生表单校验，存储输入步长由 1024 调整为 1 MiB。
- `scripts/release/snapshot.sh local` 已完成快照临时发布与隔离 Forge 缓存的仓库外消费；新增 `SDK snapshot integration` 工作流每日北京时间 10:00 发布并运行公共契约消费测试。远端发布与消费结果见下文。
- `ai-platform` 仓库当前身份无法找到，工单 01 尚未建立工程；完整 AI 产品的跨仓每日集成仍由工单 01 接入，最后一项在这部分验收前保持未勾选。

- 本地完整后端 `./mvnw -o install -Parchetype-it` 通过；前端类型检查、436 项测试、格式/样式检查通过；权限目录 20 项、发布脚本 12 项测试与真实目录导出 `--offline --check` 通过。完整视觉回归 204/208 通过，4 项为未改动字典页面的旧基线差异（旧截图缺少字典项标签、仍写首页），本次套餐页 6 项全部通过。
- 远端 Main flow E2E 与 Template smoke 已通过。Frontend quality 为 61/65 E2E 通过，4 项 federation 精确菜单定位失败；基线 `a97bc394` 的 run 43549567400 已有相同 4 项失败，本次 run 43554518161 未新增失败。相关租户端、共享 SDK/UI 与 federation 用例均未改动。
- SDK `0.2.0-SNAPSHOT` 已发布至 GitHub Maven Packages，隔离 Forge 缓存的仓库外消费测试通过：[SDK snapshot integration 43554518109](https://github.com/crane199709/saas-forge/actions/runs/43554518109)。远端 Backend quality 43554518188 也已通过；功能提交 `2c8bebd6` 已推送 main。平台每日快照与公共契约验证已落地，AI 产品实际跨仓构建仍待工单 01。
