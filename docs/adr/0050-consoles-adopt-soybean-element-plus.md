# 两个 Console 采用 Soybean Admin Element Plus 并清除旧 UI 体系

2026-09-15，用户将本轮尚未提交的底座选择改为官方 [Soybean Admin Element Plus](https://github.com/soybeanjs/soybean-admin-element-plus)。两个正式 Console 沿用模板布局和组件范式，以 Vue 3 页面重写现有功能；先完成平台端，再迁移租户端，Tenant/Plan 为首批验证页面。

模板接管布局、主题、导航与通用 UI。保留纯 TypeScript 的 api-client 和 app-runtime，使用 Vue 接入认证及服务端权威的操作恢复；两个 Console 继续具有各自的受控 Origin、会话槽位和 Runtime 实例。品牌、国际化、权限、幂等和浏览器安全语义保持。

本决定替代 ADR 0037 的自建 Design System 和固定 Ant Design 实现约束，以及 ADR 0047 的自建 A 方案和样板范围。本决定同时更新 ADR 0042 的包所有权名称与 ADR 0040 的 Locale 实现载体：二者的决策本身仍然有效，改变的只是承载实现。ADR 0039 的共享认证 Runtime 原则保留，React Shell 实现退出；按已确认的切换方式，每端验收后整体替换正式入口。

后续 [ADR 0051](0051-consoles-use-complete-soybean-applications.md) 细化了本决定的完整应用接入范围：共享 UI 载体将从 `@saas-forge/admin` 迁入两个官方应用结构。

最终清除旧 React UI、旧应用壳、兼容层、原型、废弃依赖与入口，并更新失效文档和检查。必要业务、安全及无障碍测试迁入 Vue 实现后保留覆盖。先前接入但未接管正式入口的 Skyroc 代码和新增依赖立即退出，不作为兼容层保留。实施进度见 [重构计划](../archive/console-soybean-element-plus-refactoring.md)。
