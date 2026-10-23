# 16: 租户角色管理

**What to build:** Tenant Admin 在 tenant-app 创建、编辑、删除自定义 Role，为 Role 勾选 Permission Code（来自 Resource Manifest），并把 Role 分配给 Membership。变更即时生效：被调整的成员下一次请求就按新权限处理，菜单与按钮随之变化。

**Blocked by:** 07 Token 即时失效与刷新, 12 资源清单与功能权限校验

**Status:** ready-for-agent

- [ ] Role 的增删改与权限码授权；预置角色（Owner、Tenant Admin）不可删除
- [ ] 权限码按服务与分组展示，失效的权限码不可再授予
- [ ] 一个 Membership 可拥有多个 Role，权限取并集
- [ ] 角色或授权变更时递增受影响 Membership 的 version
- [ ] 业务服务在 Provisioning 时预置的角色模板可见、可编辑
- [ ] tenant-app 提供角色列表、权限勾选与成员分配界面
- [ ] 测试：撤销某权限后该成员下一次请求得到 403，菜单同步消失
