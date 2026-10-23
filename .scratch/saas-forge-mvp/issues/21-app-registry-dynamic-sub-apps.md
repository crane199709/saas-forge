# 21: 应用注册表与子应用动态加载

**What to build:** Operator 在 platform-console 的 App Registry 中登记 Sub-App（编码、各环境 `remoteEntry` 地址、版本、所需最低共享依赖版本）。tenant-app 按当前成员的菜单与租户套餐允许的 Sub-App 在运行时加载对应页面；修改注册表中的地址即可切换 Sub-App 版本，Shell App 无需重新发布；切换租户时卸载全部 Sub-App 并清空共享状态。

**Blocked by:** 02 Module Federation 技术验证, 12 资源清单与功能权限校验, 20 套餐

**Status:** ready-for-agent

- [ ] App Registry 的增删改与按环境的入口地址配置
- [ ] 菜单节点可指向某 Sub-App 的路由
- [ ] Shell App 启动时获取当前可加载的 Sub-App 列表，只加载套餐允许且菜单可见的 Sub-App
- [ ] 共享依赖版本低于 Sub-App 声明的最低版本时拒绝加载并提示
- [ ] Sub-App 经共享的 `@forge/sdk` 获取 Token 与租户上下文，自身不处理登录
- [ ] 切换租户时卸载全部 Sub-App、清空共享 Pinia 状态后重载
- [ ] 按 02 的结论把隔离约定写入 Sub-App 脚手架与 lint 规则
- [ ] 测试：修改注册表地址后加载到新版本；未在套餐中的 Sub-App 不被加载
