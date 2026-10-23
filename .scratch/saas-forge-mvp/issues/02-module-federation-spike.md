# 02: Module Federation 技术验证

**What to build:** 验证 ADR-0009 的微前端方案在本项目栈上可行：tenant-app 作为 Shell App 在运行时加载一个独立构建、独立部署（独立端口）的示例 Sub-App，两者以 singleton 共享 Vue、Vue Router、Pinia、Element Plus、`@forge/ui`、`@forge/sdk`。产出验证结论，并据此修订规格第 12 节的 Sub-App 接入契约。

**Blocked by:** 01 工程骨架与版本组合验证

**Status:** ready-for-agent

- [ ] Shell App 通过运行时配置的 `remoteEntry` 地址加载示例 Sub-App 页面
- [ ] 共享依赖在浏览器中只存在一份实例（可通过运行时检查证明）
- [ ] Sub-App 内的 Element Plus 弹层（Dialog、Select 下拉、Tooltip）位置与样式正常
- [ ] Sub-App 的嵌套路由与 Shell App 路由同步，浏览器前进后退正常
- [ ] 切换主题 token 后 Shell App 与 Sub-App 同步生效
- [ ] 修改 `remoteEntry` 地址指向新版本 Sub-App 后，刷新即加载新版本，Shell App 无需重新构建
- [ ] Sub-App 的 scoped CSS 不影响 Shell App 样式
- [ ] 在本目录下记录验证结论（可行性、坑点、对接入契约的修订）；如结论推翻 ADR-0009，标注需重新决策
