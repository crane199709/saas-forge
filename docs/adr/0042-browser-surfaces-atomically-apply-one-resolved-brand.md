# 浏览器界面只原子应用一个 Resolved Brand Profile

> 当前载体：完整 Soybean 单 Console 已在独立 saas-forge-web 维护，见 [ADR 0052](0052-unified-console-authentication-uses-versioned-session-protocol.md) 与[#207 清理记录](../acceptance/issue-207-cleanup.md)。下文自建包、React Shell、旧 `consoles/` 路径及迁移时态保留历史事实，不作为现行入口；单一展示来源、权威品牌原子应用和无凭据 Remote 边界继续有效。

Tenant Access 继续按 [ADR 0036](0036-tenant-access-owns-controlled-tenant-brand-profiles.md) 拥有 Tenant Brand Profile，Design System 继续按 [ADR 0037](0037-browser-surfaces-use-one-shared-design-system.md) 拥有唯一 Theme Provider，`app-runtime` 与共享 React Shell 继续按 [ADR 0039](0039-consoles-share-one-authentication-runtime.md) 分别拥有无 UI 认证状态与 React 组合。MVP 的 Platform Brand Profile 由 Design System 内的版本化构建期常量提供，不扩展 Runtime Config；它与 Tenant Brand Profile 都由显示名称、Logo、favicon、主色和强调色组成。未建立权威 Tenant Context 时只使用平台品牌，已发布 v1 契约仍兼容可选 Logo/favicon，但新写入只产生五字段完整 Tenant Profile，旧快照缺失任一字段时整份无效。

`app-runtime` 只原子发布权威 Tenant Context 与原始品牌快照，不解析视觉语义；Design System 是唯一品牌解析边界，它必须在提交前整体校验 Profile、受控同站素材引用、Logo/favicon 加载与允许 MIME，并生成包含规范化完整 Profile、浅色/深色 Brand Token Set 与 `platform | tenant` 来源的不可变 Resolved Brand Profile。共享 React Shell 是唯一运行时应用缝；Theme Provider、显示名称、Logo、favicon 与标签页标题只能消费同一 Resolved Brand Profile，Console 与 Remote 不得直接消费原始 Profile。Remote 只继承 Shell 已提交的 Brand Token Set，不获取或渲染品牌素材。

Tenant Context Switch 提交后立即清除旧 Tenant 品牌并完整回到 Platform Brand Profile；只有新 Context、Profile 与素材均有效时才提交新 Resolved Brand Profile。“原子”表示全部品牌表面始终来自同一个已解析 Profile，不承诺浏览器将 favicon 与 React/CSS 在同一帧绘制。任一字段、受控引用或素材加载失败都使整份 Tenant Profile 失效并回退平台品牌，但不阻断已合法建立的 Tenant Context。拒绝只暴露稳定、不含原始值的原因码与可测试回调；本决策不前移新的前端遥测系统。

品牌快照不进入浏览器持久存储或跨标签页消息，接收页继续从禁止缓存的 Current Tenant Context 权威回读。除 Session Slot 代次外，Context/品牌读取使用本页单调读取代次防止同一 Context 的迟到旧响应回填。第 4 阶段管理页预览只能在隔离容器中复用解析器，保存成功后以 revision/ETag 与权威返回或回读更新真实 Shell，不使用时间戳猜测新旧。实际平台 Logo/favicon 素材必须在实现前另行确认，验收夹具不是产品品牌。

本文的包所有权名称已随 [ADR 0050](0050-consoles-adopt-soybean-element-plus.md) 更新：品牌解析位于 `consoles/shared/admin` 的 `brand/resolved-brand.ts`，运行时应用缝是 `shared/admin` 的 `mountConsole` 与各 Console 入口；`@saas-forge/design-system` 与共享 React Shell 已退出，`app-runtime` 继续只发布无 UI 的权威 Context 快照与原始品牌快照。品牌唯一解析、单份不可变 Resolved Brand Profile、失败时整份回退平台品牌、不进入浏览器持久存储等决策继续有效。
