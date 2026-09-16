# 共享响应式布局消费与浏览器验证记录

> **已归档**：本文描述的实现与界面已被删除，仅作为历史证据保留。其中引用的 `consoles/shared/design-system`、`consoles/shared/react-shell` 与 React/Ant Design 页面已由 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替代为 Vue 3 + Element Plus + Soybean Admin。当前实现见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [本地分层验证](../local-verification.md)。

> 当前阶段范围已由 [ADR 0046](../adr/0046-development-supports-chrome-and-jdk17.md) 调整为桌面 Chrome 当前稳定版与 JDK 17；Chromium 保留日常功能与视觉测试。本文旧矩阵的执行结果属于历史证据，不作为当前多浏览器或 JDK 21 要求。现行复现入口见 [本地验证说明](../local-verification.md)。

**状态：Issue #106 的代表性 Remote 消费、静态边界门禁、四浏览器 CI 行为与 Chromium 视觉证据已建立；本机 Darwin/Chromium 抗锯齿基线已独立校准，两个 Console 尚无最终产品业务路由消费。**

## 1. 验证边界

`design-system-consumer-fixture` 继续只是代表性 Remote 消费夹具，不是产品领域页面。Remote 从 `@saas-forge/design-system` 根入口实际渲染全宽 `PageLayout`、`content` 与 `compact-statistics` 两种 `ResponsiveGrid` 意图，以及具有可访问名称的 `SplitLayout`。它不安装 `DesignSystemProvider`、不导入全局 CSS、不依赖 `antd`，也不覆盖公共布局的内部选择器、断点或尺寸。

验收宿主安装唯一 `DesignSystemProvider` 和 Design System 根入口附带的唯一全局样式。Platform Console、Tenant Console Shell 与 Remote 都声明 `@saas-forge/design-system: workspace:*`，解析到同一正式包版本 `0.1.0`。生产制品门禁要求三个消费者各自只有一个 CSS 文件，且三个文件的内容哈希完全一致。

本票不修改两个 Console 的产品路由。Remote 夹具只能证明公共布局能力与共享消费边界已经交付，不能证明 Platform Console 和 Tenant Console Shell 已在最终产品业务路由中消费这些布局。因此 [MVP 开发计划](../16-mvp-development-plan.md)中的“响应式栅格和标准分栏布局”继续保持未完成，父 Issue #103 也不能据此关闭。

## 2. 静态边界失败用例

`pnpm run test:boundaries` 对下列违规提供明确失败结果：

- 从 `antd` 或 `antd/es/grid` 直接导入底层组件；
- 从 `@saas-forge/design-system/*` 导入内部入口；
- 导入消费者全局 CSS；
- 覆盖 `.ant-*`、`.sf-page-*`、`.sf-responsive-grid*` 或 `.sf-split-layout*` 内部选择器；
- 以 `PageLayout`、`ResponsiveGrid` 或 `SplitLayout` 名称重复实现已有公共布局；
- Remote 安装 Provider，任一 Shell 未安装或重复安装 Provider；
- 任一消费者脱离 `workspace:*`，或三个消费者解析到不同 Design System 版本。

## 3. 浏览器行为矩阵

代表性 Remote 的自动浏览器验证固定以下可用宽度：

| 可用宽度 | 普通内容列数 | 紧凑统计列数 | 主辅布局 |
| -------- | -----------: | -----------: | -------- |
| 1440px   |            3 |            4 | 左右分栏 |
| 1280px   |            3 |            4 | 左右分栏 |
| 768px    |            2 |            3 | 上下堆叠 |
| 390px    |            1 |            1 | 上下堆叠 |
| 360px    |            1 |            1 | 上下堆叠 |
| 320px    |            1 |            1 | 上下堆叠 |

测试还在 `1440px` 浏览器窗口内把 Remote 内容容器限制为 `40rem`，直接得到普通内容两列、紧凑统计三列和主辅上下堆叠，证明布局依据组件实际空间，而不是整个窗口宽度。

浏览器断言覆盖页面标题身份、非空主体、两种栅格的普通内容语义、命名辅助地标、主内容先于辅助栏的 DOM 与 Tab 顺序、可见焦点、辅助栏不隐藏、页面无横向溢出、无 Vite 错误层、无未处理运行时错误及无相关控制台错误或警告。桌面与窄屏稳定视觉基线经过重新生成和人工检查；窄屏基线使用足够页面高度展示全部统计项、主内容和辅助栏，不以截断截图代替布局证据。

## 4. 可复现验证

```bash
cd consoles
pnpm run test:boundaries
pnpm run test:browser:compatibility
pnpm run verify:workspace
```

当前兼容入口仅覆盖真实 Chrome；Chromium 保留为日常功能与视觉测试工具。

工作区聚合验证继续覆盖 Design System、Platform Console、Tenant Console Shell、Remote 消费夹具的类型检查、格式、Lint、单元测试、Chromium 浏览器测试与代表性生产构建。既有 Console 启动状态、路由标题焦点，以及 Design System 表单、表格、危险确认和焦点恢复行为仍由原有测试集回归。

## 5. 2026-08-31 本机结果与边界

- `pnpm run test:boundaries` 通过，6 个边界测试全部成功。
- Remote Chromium 行为与更新后的桌面、390px 视觉基线通过，5 个消费者浏览器测试全部成功；应用内 Browser 另行确认 1280px 与 390px 页面身份、完整 DOM、几何顺序、提交反馈和控制台健康。
- 标准 `pnpm run verify:workspace` 通过，包含全部类型检查、Lint、格式、83 个单元测试、Design System 9 个 Chromium 浏览器测试、消费者 5 个 Chromium 浏览器测试、三个消费者生产构建和制品哈希门禁。三个消费者构建产物继续只有一个且内容完全相同的 CSS 入口。
- 真实 Chrome 兼容入口通过：Design System 6 个行为测试和消费者 4 个行为测试成功，视觉用例按跨浏览器约定跳过。
- GitHub Actions Run [33397338968](https://github.com/crane199709/saas-forge/actions/runs/33397338968) 中，Console browser Chrome、Edge、Firefox 与 Safari agreement（WebKit）四个独立 Job 全部成功。整个 Run 的失败来自与 #106 无关的 `Tenant lifecycle fresh-volume E2E`，不改变四浏览器门禁的直接结果。
- 本机连续两次运行 Design System Chromium 快照时，实际图片逐字节一致；差异只位于中英文文字与数字的字形边缘，截图尺寸、卡片边框、间距、分栏和断点排列均未变化。据此独立更新本机 Darwin/Chromium 的 8 张 Design System 基线，不改动 Linux CI 基线或测试容差。
- 本机 Edge 安装权限、Firefox headless 插件子进程和 WebKit 焦点行为仍是 macOS 本地环境边界；四浏览器兼容结论采用上述 CI 受控环境的直接结果，不再以本机安装或运行成败代替。

因此，#106 已取得公共布局、Remote 共享消费、生产制品、Chromium 视觉与四浏览器行为的直接证据。本记录不扩大到两个 Console 的最终产品业务路由；MVP 对应计划项与父 Issue #103 仍不能据此关闭。
