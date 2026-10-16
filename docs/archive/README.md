# 归档

本目录存放**已被取代**的历史文档。它们记录当时真实发生过的设计决策、验证过程与证据，但其中描述的实现可能已经被删除。

归档不等于删除：这些文档保留原始事实，用于追溯"当时为什么这样做"。任何实现、验收或勾选判断都不得以本目录内容为依据。

## 归档原因分类

| 原因 | 含义 | 现行依据 |
| --- | --- | --- |
| 界面代际更替 | 文档描述自建 React / Ant Design 控制台，已被 Vue 3 + Element Plus + Soybean Admin 取代 | [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md)、[Console 设计规范](../25-design-system.md) |
| 前端迁出 | 文档描述本仓 `consoles/` 内的实施计划或浏览器验收，该目录已迁至独立 saas-forge-web | [迁出记录](../acceptance/consoles-extraction.md)、[saas-forge-web](https://github.com/crane199709/saas-forge-web) |

## 内容清单

| 文档 | 归档原因 |
| --- | --- |
| [26-design-system-consumption-verification.md](26-design-system-consumption-verification.md) | 验证自建 `@saas-forge/design-system` 的消费边界；该包已删除 |
| [27-shared-responsive-layout-consumption-verification.md](27-shared-responsive-layout-consumption-verification.md) | 验证自建共享 React Shell 的响应式布局；该包已删除 |
| [console-login-ui.md](console-login-ui.md) | 自建登录壳的视觉改造记录；该壳已删除，现行登录页见 [Issue #191 验证记录](../acceptance/issue-191-platform-soybean.md) |
| [console-tenant-ui-a.md](console-tenant-ui-a.md) | Tenant 界面「A 方案」验证记录；对应的界面代际已删除 |
| [console-soybean-element-plus-refactoring.md](console-soybean-element-plus-refactoring.md) | 本仓 Console 迁到 Soybean 的实施计划；已实施，决策见 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md)、[ADR 0051](../adr/0051-consoles-use-complete-soybean-applications.md)，`consoles/` 已迁出 |
| [32-stage2-browser-acceptance.md](32-stage2-browser-acceptance.md) | 第 2 阶段浏览器聚合验收规格；浏览器侧由 saas-forge-web 承接，后端侧见 [Stage 2 聚合](../agents/stage2-aggregation.md) |

## 与 `docs/acceptance/` 的分工

- `docs/acceptance/` 保留**同一界面代际内**的验收记录，并统一标注历史证据边界，其中的技术结论（后端、契约、迁移、安全）通常仍然成立。
- `docs/archive/` 存放**界面代际已被整体替换**或**载体已删除**的文档，其结论整体失效。
