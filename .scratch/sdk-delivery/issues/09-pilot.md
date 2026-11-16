# 09: 仓库外试点接入

**What to build:** 一个真实的内部团队在自己的仓库里，按接入指南，用正式发布的制品从零接入一个真实的 Business Service（如有界面，同时接入一个 Sub-App），部署到试点环境，并把遇到的问题反馈回平台。这是规格目标「SDK 能被仓库外团队真实接入」的最终验收。

**Blocked by:** 无（07、08 已完成；试点按下述用户确认验收）

**Status:** resolved

## 验收（2026-11-16 按用户确认调整）

- [x] 仓库外试点工程采用 `lowcode` 与 `ai-platform`，分别位于 `/Users/liuhuan/workspace/coding/sub/lowcode` 与 `/Users/liuhuan/workspace/coding/sub/ai-platform`
- [x] 以两个独立工程已有的平台接入结果完成仓库外接入验收
- [x] 接入反馈与平台修复记录由 [低代码验证](../../lowcode-validation/spec.md)、[服务接入](../../service-onboarding/spec.md) 与 [AI 平台](../../ai-platform/spec.md) 的工单及验收记录承接
- [x] 用户确认上述两个工程已满足本工单的试点目标

## Comments

### 2026-11-16 用户确认完成

用户确认已有 `lowcode` 与 `ai-platform` 两个仓库外接入工程，本工单标记完成。最终验收采用这两个工程的实际接入结果，替代原先另行组织人工团队、限定平台维护者参与方式及统计试点工时的流程。接入证据见上面的规格与工单记录。
