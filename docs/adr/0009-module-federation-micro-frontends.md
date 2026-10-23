# 业务前端采用运行时微前端，框架选 Module Federation

业务服务的前端作为 Sub-App 独立构建部署，由 Shell App 在运行时加载，版本切换只改 App Registry 中的入口地址，主应用无需重新发布。框架选 `@module-federation/vite`：原生支持 Vite ESM，且是唯一能在运行时以 singleton 共享 Vue、Element Plus、`@forge/ui`、`@forge/sdk` 的方案。它不提供沙箱，CSS/JS 隔离依靠约定（scoped CSS / CSS Modules、主题 token、禁改全局样式与全局路由），这成立的前提是 Sub-App 均由内部可信团队开发；若将来接入不可信的第三方页面，需另行评估 iframe 方案。登录、Token 刷新与租户切换只由 Shell App 负责。

## Considered Options

调研于 2026-10：

- qiankun：2.x 依赖已停更的 vite-plugin-qiankun；3.x 仍是 rc，ESM 沙箱下子应用无 HMR，挂到 body 的弹层脱离样式隔离。
- wujie：Shadow DOM 隔离最强，但 Element Plus 弹层定位、富文本编辑器问题多，无法共享组件库，2026-06 后无提交。
- micro-app：1.0 长期处于 rc，Vite 只能用 iframe 沙箱，不共享运行时实例。
