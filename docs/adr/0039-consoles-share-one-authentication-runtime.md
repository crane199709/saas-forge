# 两个 Console 共用一个认证 Runtime

> 目标更新：#201/#202 已确认一个 Console、同 Origin 标签页共用当前 Identity 和工作上下文；见 [ADR 0052](0052-unified-console-authentication-uses-versioned-session-protocol.md)（v2 已实现，启用仍须受控切换）。每 Realm 一个 Runtime、无 UI 认证边界和正式类型化 Client 继续有效；下文双应用/双槽位与旧 UI 迁移描述仅供历史追溯。

Platform Console 与 Tenant Console Shell 使用同一套认证状态、HTTP、Problem Details、导航和错误恢复语义，但分别在各自受控 Origin 创建实例并只持有对应 Browser Session Slot 的内存 Access Token。`@saas-forge/app-runtime` 保持无 UI 框架依赖，以纯 TypeScript 状态转换、类型化 `ConsoleApiClient`、内存 Token 与 Problem 规范化封装生成 API Client；另一个共享 UI 包拥有 Provider、路由守卫、全局导航和根/路由错误边界，并由共享组件包提供它们的统一视觉与交互实现（当前载体是 `@saas-forge/admin`，见文末说明；[ADR 0051](0051-consoles-use-complete-soybean-applications.md) 已决定把该载体迁入两个官方应用结构，尚未实施）。

每个 Console 页面 Realm 只有一个 Runtime，同 Origin 标签页通过浏览器原子锁与跨标签页消息协调会话变更，浏览器能力不可用时以 IAM Refresh Rotation Lease 作为最终并发边界。Access Token、密码、Membership 候选与 Problem 不持久化；只允许持久化不敏感的槽位单调代次与 `logoutPending`。Remote 和页面不能读取 Token、创建第二个 Runtime 或发起携带凭据的任意 Fetch；它们只能调用共享 Client 暴露的正式类型化 operation。

状态机、刷新/重放、幂等句柄、错误分层、Tenant Context Switch 不可回滚过渡、浏览器兼容与 Fresh Compose 完成边界见 [Console 认证 Runtime 与浏览器会话规格](../28-console-authentication-runtime.md)。该规格不提前实现 Manifest/Remote 导航注册、完整国际化资源或前端遥测平台。

本文写就时共享 UI 载体是自建共享 React Shell 与 `@saas-forge/design-system`，二者已由 [ADR 0050](0050-consoles-adopt-soybean-element-plus.md) 替换为 `@saas-forge/admin`（Vue 3 + Element Plus + Soybean Admin）：Provider、路由守卫、全局导航与根/路由错误边界现由 `@saas-forge/admin` 提供。该载体本身将按 [ADR 0051](0051-consoles-use-complete-soybean-applications.md) 迁入两个官方应用结构，尚未实施。本文的核心决策——每个 Console 页面 Realm 只有一个 Runtime、同一 Origin 内协调会话、Remote 与页面不得创建第二个 Runtime 或读取 Token——继续有效；`app-runtime` 无 UI 的边界也继续有效。
