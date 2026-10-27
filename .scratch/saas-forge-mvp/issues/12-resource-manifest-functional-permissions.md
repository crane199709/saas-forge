# 12: 资源清单与功能权限校验

**What to build:** Business Service 只需在接口上声明 Permission Code，并在清单中补充名称、分组与菜单，SDK 就会在启动时把版本化的 Resource Manifest 上报到 iam。每个租户预置 Owner 与 Tenant Admin 两个角色。demo-service 的接口按权限码拦截；tenant-app 按后端下发、已按权限过滤的菜单生成路由，按钮用 `v-perm` 控制显示。

**Blocked by:** 06 多租户成员与切换租户

**Status:** ready-for-agent

- [x] SDK 扫描 `@RequiresPermission` 收集权限码，合并清单文件中的名称、分组与菜单树
- [x] 启动时上报清单（也支持在 CI 中生成与上报）；iam 按 `service + version` 存储，下线的权限码标记失效，不物理删除
- [x] 每个租户预置 Owner（唯一、不可删除）与 Tenant Admin（拥有全部有效权限码）角色
- [x] demo-service 无权限访问返回 403
- [x] iam 为当前 Membership 下发其可见菜单树
- [x] tenant-app 按菜单动态生成路由；`@forge/sdk` 提供 `v-perm` 指令与权限查询
- [x] 测试：有权限与无权限的 Membership 分别访问同一接口得到 200 与 403；菜单按权限裁剪


## Comments

2026-10-27 实现：
- SDK：`@RequiresPermission` 在 forge-sdk-security，以基础设施 Advisor 生效，服务无需 `@EnableMethodSecurity`；按 `MembershipGrantStore` 中当前版本的明细判断，缺权限返回 403 `auth.forbidden`。Platform Realm 服务上的声明暂一律拒绝（TODO #22）。清单契约 `ResourceManifest` 放在 forge-sdk-core，iam 与 SDK 共用。
- forge-sdk-manifest：`ResourceManifestCollector` 只读类文件元数据扫描注解，合并 `META-INF/forge/manifest.json` 的名称、分组与菜单（可额外声明仅前端按钮用的权限码；菜单引用未声明的权限码则启动失败）。启动完成后上报 iam，上报失败只记日志不阻止启动；CI 用 `ManifestCli`，令牌取自 `FORGE_MANIFEST_TOKEN`。配置 `forge.manifest.{version,iam-uri,token}`，开启时必填。
- iam：`POST /internal/manifests` 以 `forge.iam.manifest.tokens` 中按服务配置的令牌认证，服务只能上报自己的清单。`service + version` 不可变：同版本同内容为无操作（旧实例重启不会切回旧版；相应地，回滚到已上报过的旧版本也不会恢复旧清单，需以新版本号重新上报），内容不同 409 `manifest.versionConflict`；权限码全局唯一，被其他服务占用时 409。新版本中不再出现的权限码标记 `inactive`，再次上报时恢复。
- 预置角色：`role` 表中 `builtin` 与保留编码 `owner`/`tenant-admin` 由 CHECK 绑定且不可删除；`membership_role.owner` 经复合外键与角色一致，部分唯一索引保证 Owner 唯一持有者。V7 为已有租户补齐，新租户经 `RoleService.provisionBuiltinRoles` 幂等开通（待工单 14 调用）。Owner 与 Tenant Admin 不落授权记录，发布明细时取全部 active 权限码；签发 Token 时发布真实明细，因此新上报或下线的权限码在成员下次刷新 Token 后进入 Redis 明细，SDK 本地缓存同一版本最多保留 1 分钟后重读。V7 只为已有租户补齐角色行，不指定 Owner 持有者（只有演示租户由种子指定），已有成员在授予角色前没有权限，与此前明细为空的行为一致。
- `GET /auth/access` 按数据库中的当前角色返回权限码与裁剪后的菜单树：缺权限的节点连同子树隐藏，子节点全被隐藏的分组也隐藏。
- 前端：`@forge/sdk` 新增 `createAccessControl`（`load/has/menus/clear`，作为插件安装时注册全局 `v-perm`）。tenant-app 在首次导航前按菜单注册 Sub-App 路由，Membership 变化时重新生成；粒度是 Sub-App（菜单可见才挂 `/apps/<app>`，其详情页随之注册），导航按菜单生成。demo-sub-app 概览页的「新建订单」按钮由 `v-perm="'order:create'"` 控制。
- 验证：forge-sdk-security 新增 3 项、forge-sdk-manifest 5 项、iam `ResourceManifestTest` 5 项、demo-service `FunctionalPermissionTest` 2 项（同一接口 200/403、清单生成）；既有订单测试改为先发布明细。前端 57 项单测、typecheck、format:check 与 11 项 Playwright（新增 v-perm 显示/隐藏与菜单外 Sub-App 不注册路由）通过。后端 `./mvnw verify` 全部通过。另以临时 Postgres/Redis 实际启动 iam 与 demo-service：demo-service 启动后上报 `demo-service@1`，演示 Owner 经真实登录取得 Token 后 `/auth/access` 返回全部权限与菜单、`/orders` 返回 200；未经网关。
- 双轴审查后修复：SDK 明细缓存加 1 分钟刷新；Owner 标志与预置角色改由数据库约束兜底；清单校验下沉到 `ResourceManifest.validate()`，iam 与 SDK 共用（iam 补上叶子节点与菜单编码重复校验）；上报开关只保留 `@DefaultValue`；菜单变化后当前 Sub-App 路由被移除时回到首页。前端判定（`/auth/access` 实时计算）与后端拦截（Redis 明细）在 Token 刷新前可能短暂不一致，属已知取舍。
