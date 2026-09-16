# Issue #147 完整品牌边界与浏览器验收

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

## 范围与实现

本记录覆盖 #142–#147 的完整品牌运行时链路：Runtime 只发布权威 Tenant Context/原始品牌快照，Design System 唯一解析完整 Profile 与 Token，共享 React Shell 唯一应用名称、Logo、favicon、标签页标题与浅色/深色主题。Console 只整份转交结果；Remote 只继承 Token 和布局。

#147 移除旧 Profile/Provider 兼容出口，自动发现 Console/Remote 并拒绝字段读取、内部导入、重复 Provider、独立品牌素材与 CSS/HTML Token 覆盖。同时修复品牌缺字段导致合法 Tenant Context 被拒绝的问题，以及窄屏语言控件遮挡品牌名称的问题。

## 直接证据

| 验收边界 | 证据 |
| --- | --- |
| 旧入口零消费者、后续消费者自动发现、禁止直接品牌消费 | `consoles/test/design-system-boundaries.test.mjs`，含别名、解构、新建 Console/Remote、HTML 与 CSS 负向样本 |
| 完整品牌解析、稳定原因码与合法 Context 保留 | Design System resolver、app-runtime、Tenant Console 包级测试；五个字段逐一缺失 |
| 无 Context、读取中、恢复/错误与切换中间态平台品牌 | 消费者浏览器测试与 Fresh Compose 产品测试；`context-reading-platform.png`、`switch-refresh-failed-platform.png` |
| 合法 Tenant 五项共同生效，浅色/深色主题 | 真实素材请求、解码、全局 Logo、标题、favicon 与 computed Token 断言；`tenant-blue-light.png`、`tenant-violet-dark.png` |
| 缺字段、非法名称/颜色、外部 URL、404/MIME/解码失败 | Fresh Compose 产品测试的 12 类独立负向子测试，全部检查整份回退并保持工作台可用 |
| Switch 204 清除旧品牌，刷新失败保持平台，再恢复目标品牌 | 实际 Tenant Switch 与 Refresh 响应、受控断网和 UI 重试；`switch-committed-platform.png` |
| 迟到旧 Context 不覆盖新品牌、原生多标签页权威恢复 | `brand-concurrency-acceptance.mjs` 延迟真实 GET 响应、触发新权威回读再交付旧响应；`late-context-newest.png` |
| Remote 只继承 computed Token 和共享布局 | `brand-remote-acceptance.mjs` 检查单 Provider、四个颜色 Token、共享 main、无品牌图片、额外 favicon、Context 或品牌素材请求；允许浏览器重取 Shell 已有且未改变的 favicon |
| 存储、可读 Cookie、消息与生产日志无品牌值 | `expectSafeStorage`、原生消息结构白名单和浏览器/服务日志敏感值检查；拒绝原因回调只观察枚举码 |

## 本地验证

2026-09-07，实际实现基线为 `cc63cbf` 加 #147 改动，随后提交为 `0b2e225`。

- `./mvnw --batch-mode --no-transfer-progress verify`：25 个 reactor 模块全部成功，包含前端类型、Lint、格式、边界/单元/Chromium 浏览器测试、生产构建和制品检查。
- Chromium Fresh Compose：30/30 通过，0 失败、0 跳过。最终项目 `saas-forge-console-1788778329-39197-d83420`。
- Playwright WebKit Fresh Compose：30/30 通过，0 失败、0 跳过。项目 `saas-forge-console-1788777704-21736-c7e2ee`。
- 每轮使用独立随机项目和全新数据卷，经正常 TLS 校验访问真实 Gateway/服务与两个 Console；结束后已读取确认对应容器和数据卷清理。
- Chromium 的 22 张真实产品截图保存在 [issue-147-chromium](assets/issue-147-chromium/)；已人工核查品牌、窄屏布局、整份回退和迟到响应结果。

## 远程 CI

2026-09-08，提交 `5587a7339e716e3159e41aebdf0481512b136958`：

- [PR Verify](https://github.com/crane199709/saas-forge/actions/runs/34171356002)：全部 8 个任务通过，包含 JDK 17/21、Tenant 生命周期、Nacos 与四浏览器兼容验证。
- [PR Fresh Compose 五浏览器验收](https://github.com/crane199709/saas-forge/actions/runs/34171355987)：Firefox、WebKit、Chromium、Chrome、Edge 分别 30/30 通过，全部 0 失败、0 跳过；随后四项兼容命令全部通过。
- CI 曾暴露 Remote 断言将浏览器重取现有 favicon 误计为 Remote 素材请求。Chrome 本地重现后，仅豁免浏览器 `other` 类型对 Shell 原有 favicon 的同 URL 请求，并继续断言 favicon 未变、单 Provider、无图片/Fetch/Context 请求；本地 Chrome 复验 30/30，最终五通道 CI 全部通过。
- 早期单次 Cookie 数量与 WebKit 多标签页断言失败未确定根因，未放宽原断言；增加的 Cookie 诊断仅输出数量，后续上述提交的相关检查通过。另一次重复 push 验收在 Compose 启动阶段失败，未将其作为产品验收成功；新增受限状态摘要只输出已知服务的运行状态、健康状态与退出码。

以上链接固定到实际通过的实现提交。验收记录、截图与状态回写的最终提交仍须通过 PR 检查后合并；合并后另核验 master 对应提交的 CI。

## 证据限制

- 品牌尚无写入 API。夹具只对本轮隔离数据库写入读模型，验证通过真实 HTTP 与浏览器呈现完成。
- 缺 displayName/primaryColor/accentColor 采用负向故障注入：先取得真实 Refresh 响应，再仅移除该品牌字段，不伪造成功认证、Token 或 Context。其余故障来自隔离数据库或真实静态素材响应。
- 代表性 Remote 是现有静态消费夹具，仅组合进独立 Tenant 验收构建；它证明 Token/布局继承，不代表 Manifest、Module Federation 或网络 Remote 加载。正式产品路由未增加验收入口。
- 验收构建仅暴露稳定拒绝原因码和原 Runtime 恢复操作的布尔结果，不暴露 Runtime、Token 或 Profile 对象。
- 本地 Firefox 启动受到 macOS sandbox_extension/plugin-container 权限与 SWGL 错误阻断；Firefox 核心行为已由上述 Linux 真实产品 CI 补齐。非 Chromium 兼容任务按既有策略不比较 macOS 视觉基线，产品核心用例不得跳过。
