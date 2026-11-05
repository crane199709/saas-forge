# 02: Module Federation 技术验证

**What to build:** 验证 ADR-0009 的微前端方案在本项目栈上可行：tenant-app 作为 Shell App 在运行时加载一个独立构建、独立部署（独立端口）的示例 Sub-App，两者以 singleton 共享 Vue、Vue Router、Pinia、Element Plus、`@forge/ui`、`@forge/sdk`。产出验证结论，并据此修订规格第 12 节的 Sub-App 接入契约。

**Blocked by:** 01 工程骨架与版本组合验证

**Status:** resolved

- [x] Shell App 通过运行时配置的 `remoteEntry` 地址加载示例 Sub-App 页面
- [x] 共享依赖在浏览器中只存在一份实例（可通过运行时检查证明）
- [x] Sub-App 内的 Element Plus 弹层（Dialog、Select 下拉、Tooltip）位置与样式正常
- [x] Sub-App 的嵌套路由与 Shell App 路由同步，浏览器前进后退正常
- [x] 切换主题 token 后 Shell App 与 Sub-App 同步生效
- [x] 修改 `remoteEntry` 地址指向新版本 Sub-App 后，刷新即加载新版本，Shell App 无需重新构建
- [x] Sub-App 的 scoped CSS 不影响 Shell App 样式
- [x] 在本目录下记录验证结论（可行性、坑点、对接入契约的修订）；如结论推翻 ADR-0009，标注需重新决策

## Comments

2026-10-24 实现并验收完成：

- 结论：当前版本组合支持 ADR-0009，无需重新决策；详见 [验证结论](02-module-federation-spike-results.md)。
- tenant-app 运行时加载 demo-sub-app；六个指定共享依赖及 vue-i18n 的实际导出引用相等。
- 独立构建的 v1/v2 分别在 5175/5176 提供服务；仅修改 JSON 并刷新即可切换，Shell 构建哈希不变。
- 前端类型检查（含构建配置与 E2E）、全量构建、2 个单测、6 个 Chromium E2E、Prettier 检查通过。
- 弹层覆盖 1280×900 与 390×844；第 12 节接入契约已修订。生产 CDN、其他浏览器、开发 HMR 与跨版本共享依赖未验证。

2026-10-24 回归修复：补充 localhost 来源的 CORS 支持与回归测试，提供 `pnpm start:demo` 启动子应用。7 个 E2E、工具类型检查与格式检查通过，用户现有 localhost 开发模式 Shell 的远程加载、共享引用、路由和 Dialog 实测通过。
