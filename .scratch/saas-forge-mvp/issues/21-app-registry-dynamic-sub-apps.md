# 21: 应用注册表与子应用动态加载

**What to build:** Operator 在 platform-console 的 App Registry 中登记 Sub-App（编码、各环境 `remoteEntry` 地址、版本、所需最低共享依赖版本）。tenant-app 按当前成员的菜单与租户套餐允许的 Sub-App 在运行时加载对应页面；修改注册表中的地址即可切换 Sub-App 版本，Shell App 无需重新发布；切换租户时卸载全部 Sub-App 并清空共享状态。

**Blocked by:** 02 Module Federation 技术验证, 12 资源清单与功能权限校验, 20 套餐

**Status:** resolved

- [x] App Registry 的增删改与按环境的入口地址配置
- [x] 菜单节点可指向某 Sub-App 的路由
- [x] Shell App 启动时获取当前可加载的 Sub-App 列表，只加载套餐允许且菜单可见的 Sub-App
- [x] Sub-App 加载有超时；单个 Sub-App 加载慢或失败时，Shell App 首页、导航及其他 Sub-App 照常可用，仅该 Sub-App 区域提示错误
- [x] 共享依赖版本低于 Sub-App 声明的最低版本时拒绝加载并提示
- [x] Sub-App 经共享的 `@forge/sdk` 获取 Token 与租户上下文，自身不处理登录
- [x] 切换租户时卸载全部 Sub-App、清空共享 Pinia 状态后重载
- [x] 按 02 的结论把隔离约定写入 Sub-App 脚手架与 lint 规则
- [x] Sub-App 团队可独立本地开发：无需启动完整 Shell App 工程即可运行和调试 Sub-App（如由脚手架提供开发用宿主），并验证修改代码后的热更新（HMR）
- [x] 测试：修改注册表地址后加载到新版本；未在套餐中的 Sub-App 不被加载


## Comments

2026-10-29 实现：
- SDK：forge-sdk-provisioning 新增事件 `SubAppChanged(code, name, version, remoteEntries, sharedRequirements, removed, revision)`，Topic `forge-app`（已加入 `deploy/rocketmq/create-topics.sh`），不带租户上下文；`revision` 取自 platform 的全局序列，删除后以同一编码重新登记也更大，订阅方只应用更新的序号。
- platform：新功能包 `subapp`。V5 新增 `sub_app`（编码部分唯一索引）与关联表 `sub_app_entry`（环境→入口）、`sub_app_requirement`（包名→最低版本）。接口 `GET/POST /sub-apps`、`PUT /sub-apps/{id}`（编码不可改，整体替换）、`DELETE /sub-apps/{id}`；入口须为 http(s) 地址，环境名与编码规则同 Plan，最低版本为 x.y.z。Plan 的 Sub-App 编码不校验是否已登记（与已下线权限码的处理一致，登记后随之生效）。
- iam：新功能包 `subapp`。V16 新增 App Registry 副本 `sub_app`/`sub_app_entry`/`sub_app_requirement`（按编码保存，删除只标记并保留 revision）与 `tenant_plan_sub_app`（`TenantPlanChanged` 中的 Sub-App 编码）。`GET /auth/access` 新增 `subApps`：菜单可见、套餐允许（未登记 Plan 不限）且有当前环境入口的 Sub-App，环境由 `forge.iam.sub-app.environment`（`FORGE_ENVIRONMENT`，本地 `local`，必填）决定；指向套餐外 Sub-App 的菜单节点随之隐藏。demo profile 预置 `demo` 副本（revision 0），本地无需先登记。
- 菜单节点指向 Sub-App 路由沿用工单 12 的清单字段 `app` + `path`，无改动。
- 前端：`@forge/sdk` 新增 `SubAppEntry`、`access.subApps()`、`subAppContainerName`（容器名 `forge_app_<编码>`）与 `useTenantClient()`。tenant-app 删除 `sub-apps.json` 与 `load-sub-app.ts`，新增 `sub-app-host.ts`：可见 Sub-App 先以占位路由挂到 `/apps/<编码>`，各自后台加载（15 秒超时），失败、超时或共享依赖低于登记的最低版本（不请求入口）时只在该区域报错；导航只列出已注册的 Sub-App。切换租户经 `createShellReload` 先卸载全部 Sub-App、清空 Pinia 状态再整页重载。platform-console 新增「应用注册表」面板，套餐面板的 Sub-App 选项取自注册表。
- 脚手架与 lint：新包 `@forge/sub-app-kit`——`defineSubAppConfig`（开发模式不启用 MF，build/preview 按 02 契约暴露 `./sub-app`）、`startDevHost` 独立开发宿主（带登录栏，代理到本地网关）、`forge-sub-app-lint` 隔离约定检查、`pnpm create:sub-app <编码> <端口>` 生成工程。demo-sub-app 已迁移，`build` 前执行 lint。说明见 `docs/sub-app.md`。
- 验证：platform `SubAppRegistryTest`（增改删广播、序号递增、非法内容 400、编码冲突、删除后重新登记）；iam `PlanEnforcementTest` 新增用例（套餐外 Sub-App 不下发且菜单隐藏、修改地址后下发新地址、乱序旧变更被忽略、无当前环境入口或删除后不下发、重新登记恢复）。后端 `./mvnw spotless:check verify` 全部通过（iam 75、platform 11、gateway 23、demo-service 34）。前端 typecheck、format:check、单测（tenant-app 91、platform-console 14、lint 规则 4）与构建通过；Playwright 15 项通过，新增：修改注册表地址后刷新加载 v2、套餐外 Sub-App 不请求入口、单个 Sub-App 加载失败不影响导航与其他 Sub-App、共享依赖版本过低拒绝加载、切换租户后 Sub-App 读到新租户上下文。脚手架：生成临时 Sub-App 后 lint、typecheck、build 通过，以 Playwright 打开其开发宿主并修改组件，页面经 HMR 更新且未整页刷新，验证后已删除。
- 已知限制：E2E 中 IAM 的 `/auth/access` 为 mock，platform→Outbox→RocketMQ→iam 的真实链路未端到端运行（事件处理器按消费端方式直接调用）；超时只以单测（假定时器）验证；已有的本地 RocketMQ 需重新执行 create-topics 创建 `forge-app`；lint 基于文本匹配，不是安全沙箱；Firefox/Safari 与生产 CDN 未验证。

