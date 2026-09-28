# 浏览器界面只使用一个共享 Design System

> 当前载体：完整 Soybean 单 Console 已在独立 saas-forge-web 维护，见 [ADR 0052](0052-unified-console-authentication-uses-versioned-session-protocol.md) 与[#207 清理记录](../acceptance/issue-207-cleanup.md)。下文自建包、React Shell、旧 `consoles/` 路径及迁移时态保留历史事实，不作为现行入口；单一展示来源、权威品牌原子应用和无凭据 Remote 边界继续有效。

Platform Console、Tenant Console Shell 与全部官方业务 Remote 只通过一个版本化的 `@saas-forge/design-system` 包获得全局样式、Design Token、图标、无领域含义的控件、标准交互模式和测试工具。包内部可以使用稳定子路径组织不同职责，但消费者不得直接导入内部文件、底层组件基础或另一套同类 UI 实现；缺少通用能力时必须先扩展共享包。具体视觉与交互规则见 [Design System 规范](../25-design-system.md)。

Design System 独占全局样式入口、Theme Provider 和全部公共组件外观。现有 `app-runtime` 保持为无 UI 的启动状态机；Console 与 Remote 只能使用局部作用域的领域样式，不得定义全局 reset、覆盖 Design System 内部选择器或重写受控 Token。Tenant Brand Profile 继续遵循 ADR 0036，只能原子改变允许的品牌素材、主色和强调色，不能改变状态颜色、布局、组件、交互或无障碍约束。

MVP 阶段的两个 Console 与全部官方 Remote 必须使用完全相同的 Design System 版本。前端工作区门禁必须按目录边界自动发现当前及后续新增的 Console 与官方 Remote，不依赖人工登记消费者名单；Shell 独占全局样式和当前 Theme Provider，Remote 不得重复注入全局样式。后续 Manifest 必须声明 Design System 版本，不一致时在执行 Remote 代码前拒绝加载。是否支持第三方 Remote 的兼容版本范围留待出现真实需求后另行决策。

底层组件基础采用 Ant Design 6.6.2，并隐藏在 Design System 后。Design System 使用其 `ConfigProvider`、主题算法和 Design Token 构建统一主题，再向消费者提供由本项目控制的稳定组件接口；消费者不得在任何依赖分区直接声明或导入 `antd`，也不得覆盖 Ant Design 或 Design System 的内部选择器、受控 Token 和全局样式。该决定来自相同数据表格、校验表单和危险确认弹窗的隔离原型比较，并已由用户确认。MVP 固定使用 6.6.2；后续升级只能作为 Design System 内的独立受控工作统一进行，评审上游变更并通过组件、键盘、焦点、无障碍、视觉、Console 与代表性 Remote 回归后同步更新版本记录，不允许消费者自行升级或多版本共存。Ant Design 带来的较大初始文件、底层 API 升级和复杂表格事件耦合，由 Design System 的按需加载、版本锁定和自动化回归测试控制。

共享认证状态、HTTP 和路由组合不归 Design System 所有。[ADR 0039](0039-consoles-share-one-authentication-runtime.md) 保持 `app-runtime` 无 UI，另以共享 React Shell 组合认证 Provider、路由守卫、导航和错误边界；Design System 只继续提供它们使用的唯一视觉与交互实现。

本文的自建 Design System、固定 Ant Design 6.6.2 与共享 React Shell 实现已由 [ADR 0050](0050-consoles-adopt-soybean-element-plus.md) 替代：两个 Console 改用 Vue 3 与 Element Plus，`@saas-forge/design-system`、`@saas-forge/react-shell` 及其门禁脚本已从工作区移除，共享 UI 载体改为 `@saas-forge/admin`（该载体本身将按 [ADR 0051](0051-consoles-use-complete-soybean-applications.md) 迁入两个官方应用结构，尚未实施）。“单一 UI 来源、消费者不得自带同类实现、品牌只能原子改变、`app-runtime` 保持无 UI”等边界原则继续有效。本文提到的 Remote Manifest 版本协商尚未实现，`consoles/business-remotes/` 目前只有验收夹具。
