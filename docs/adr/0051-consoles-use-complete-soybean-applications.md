# 两个 Console 直接基于完整 Soybean 应用开发

> 目标已由 #201/#202 更新：改为已有 saas-forge-web 中一个完整 Soybean Console。#193–#199 已按计划变更关闭，不再是前置任务，也不代表旧验收通过；统一协议见 [ADR 0052](0052-unified-console-authentication-uses-versioned-session-protocol.md)（已确认、未实施）。以下状态与双 Console 范围保留为当时记录，不作为当前实施指令。

> **状态**：**已确认，未实施**。实施由 [Issue #199](https://github.com/crane199709/saas-forge/issues/199) 追踪，且被 #193–#198（各页面迁入官方应用）阻塞。
>
> 正文用完成时语态描述**目标状态**，不代表已经落地：目前只有 platform-console 已按官方应用结构切换；tenant-console-shell 仍通过 `@saas-forge/admin` 的 `mountConsole` 与自建 `Workspace.vue` 挂载，待迁移。`@saas-forge/admin` 与 `@saas-forge/i18n` 两个包当前都仍然存在。迁移范围与待办见[重构计划](../plans/console-soybean-element-plus-refactoring.md)。

2026-09-15，用户确认上一轮只复用底层布局、另行拼装认证页的实现不符合直接使用 Soybean 成熟界面的目标。两个 Console 以固定版本官方应用结构、登录页、布局、主题和导航为基础，接入本项目品牌与业务，删除演示页面和未支持入口；不合并应用、启动入口、受控 Origin 或 Browser Session Slot。

取消自建 admin 包，将其中必要业务适配迁入官方应用结构；以模板国际化机制等价替换自建 i18n 包，保留精确格式化、语言回退及 Remote 继承语义。保留纯 TypeScript app-runtime 和生成 api-client；本决定细化 [ADR 0050](0050-consoles-adopt-soybean-element-plus.md) 的完整应用接入范围，更新 [ADR 0040](0040-console-locale-is-a-local-ui-preference.md) 的 Locale 实现载体与 [ADR 0039](0039-consoles-share-one-authentication-runtime.md) 的共享 UI 载体（二者的决策内容继续有效，改变的只是承载实现），延续 ADR 0039 的认证边界，不以模板演示认证替换真实会话。

验收以两个 Console 正式页面对照固定版本上游并结合真实认证、会话及原操作恢复为准。允许品牌、业务内容和已确认交互不同；组件测试、模拟 HTTP 或构建通过不能单独证明完成，真实环境未验证项须明确保留。
