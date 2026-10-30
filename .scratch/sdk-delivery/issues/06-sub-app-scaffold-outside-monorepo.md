# 06: 前端脚手架可在仓库外运行

**What to build:** 发布后的 `@forge/sub-app-kit` 可以在任意空目录运行生成命令，得到依赖已发布 `@forge/*` 包的 Sub-App 工程。共享依赖版本取自 sub-app-kit 发布时记录的、与同版本 Shell 一致的版本表，不再读取 monorepo 中的 tenant-app。monorepo 内原有的 `pnpm create:sub-app` 继续可用。见[规格 · 前端脚手架](../spec.md#前端脚手架)。

**Blocked by:** 04 统一版本与发布流程

**Status:** ready-for-human

- [x] 生成命令接受目标目录参数，默认生成到当前目录下 `<应用编码>-sub-app`；目标已存在时失败
- [x] 构建 sub-app-kit 时，从 tenant-app 生成共享依赖版本表并打进发布产物；生成器在仓库外读取该表，在 monorepo 内继续可用
- [x] 生成的 `package.json` 依赖已发布的 `@forge/sdk`、`@forge/ui`、`@forge/sub-app-kit` 的同一版本，不使用 `workspace:` 协议
- [x] 可执行文件以编译后的 JavaScript 发布，不依赖 Node 的 TypeScript 类型剥离
- [x] 生成的工程带一个使用 `ListPage` 的列表页示例，保留独立开发宿主与 `forge-sub-app-lint`
- [x] 更新 [docs/sub-app.md](../../../docs/sub-app.md)：仓库外的生成方式、App Registry 登记与套餐开放步骤
- [x] 测试：扩展 sub-app-kit 现有的生成器测试，覆盖仓库外生成（临时目录、无 monorepo）时依赖版本取自版本表、不含 `workspace:`；monorepo 内生成行为不变


## Comments

- 2026-10-30：本地实现完成，待维护者复核；未推送或发布。测试接缝沿用本票明确指定的生成器公共命令与仓库外模板消费，不增加内部实现测试。
- 外部命令支持可选目标目录，默认当前目录下 `<code>-sub-app`，拒绝已有目标；发布包读取 `dist/versions.json`，构建时从 Shell 生成共享依赖快照。三个 Forge 包统一使用发布版本，tsconfig 独立于 monorepo。内部 `pnpm create:sub-app` 保留 apps 目录，workspace 配置按同版本链接本地包。
- 生成首页使用 ListPage、SDK 分页、ErrorAlert 与本地 i18n；保留独立宿主、业务前缀代理及隔离 lint。文档补齐仓库外生成、静态部署、App Registry、Manifest、套餐与成员权限步骤。
- TDD：仓库外命令测试先因读取不存在的 tenant-app/package.json 失败，完成后转绿；现有 lint 与新增生成器测试合计 6 个通过，sub-app-kit 类型检查通过，sdk/ui/sub-app-kit 构建与真实 pnpm pack 通过。
- 仓库外 `/tmp` 用实际 tgz 中的编译后 CLI（`node --no-experimental-strip-types`）生成工程，无 Shell 源码或父目录 tsconfig；临时 pnpm overrides 指向本次三个 Forge tgz 后，安装、lint、typecheck、build 通过并生成 remoteEntry.js。file: overrides 导致 pnpm peers check 将文件路径与 peer 的 0.1.0 作比较并报警；此项仅为本地打包消费验证，真实临时 registry 发布/安装验收由 08 完成，不能以 overrides 替代该验收。
- 仓库内原命令实际生成临时 Sub-App，lint/typecheck/build 通过；已清理临时工程，原 frozen-lockfile 安装通过。未启动真实 Shell、业务服务或验证登录/分页接口联调。
