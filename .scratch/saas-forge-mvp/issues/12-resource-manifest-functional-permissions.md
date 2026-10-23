# 12: 资源清单与功能权限校验

**What to build:** Business Service 只需在接口上声明 Permission Code，并在清单中补充名称、分组与菜单，SDK 就会在启动时把版本化的 Resource Manifest 上报到 iam。每个租户预置 Owner 与 Tenant Admin 两个角色。demo-service 的接口按权限码拦截；tenant-app 按后端下发、已按权限过滤的菜单生成路由，按钮用 `v-perm` 控制显示。

**Blocked by:** 06 多租户成员与切换租户

**Status:** ready-for-agent

- [ ] SDK 扫描 `@RequiresPermission` 收集权限码，合并清单文件中的名称、分组与菜单树
- [ ] 启动时上报清单（也支持在 CI 中生成与上报）；iam 按 `service + version` 存储，下线的权限码标记失效，不物理删除
- [ ] 每个租户预置 Owner（唯一、不可删除）与 Tenant Admin（拥有全部有效权限码）角色
- [ ] demo-service 无权限访问返回 403
- [ ] iam 为当前 Membership 下发其可见菜单树
- [ ] tenant-app 按菜单动态生成路由；`@forge/sdk` 提供 `v-perm` 指令与权限查询
- [ ] 测试：有权限与无权限的 Membership 分别访问同一接口得到 200 与 403；菜单按权限裁剪
