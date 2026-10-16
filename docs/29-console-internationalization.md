# Console 国际化基线

统一 Console 的语言规则已迁入前端仓 [saas-forge-web](https://github.com/crane199709/saas-forge-web) 的 [Console UI 与语言规则](https://github.com/crane199709/saas-forge-web/blob/main/docs/console-ui-and-locale-rules.md)，资源门禁与回归入口见该仓的 `docs/locale-and-ui-verification.md`。当前产品语言为 `zh-CN` 与 `en`；本文原先描述的浏览器语言匹配、`en-US` 回退、`sf:ui:locale`、ICU MessageFormat 与 `@saas-forge/i18n` 已不再是现行实现。

与后端相关的约束仍以本仓为准：语言偏好是本地 UI 偏好，不进入 IAM、账号或 Tenant 状态（[ADR 0040](adr/0040-console-locale-is-a-local-ui-preference.md)）；时间点、日历日期、精确小数与金额的传输格式遵循 [API 设计](08-api-design.md)，前端格式化只改变显示，不改变 API 数据。

PRD 见 [Issue #117](https://github.com/crane199709/saas-forge/issues/117)。本文迁出前的完整规格可从 Git 历史查看。
