# 06: 前端脚手架可在仓库外运行

**What to build:** 发布后的 `@forge/sub-app-kit` 可以在任意空目录运行生成命令，得到依赖已发布 `@forge/*` 包的 Sub-App 工程。共享依赖版本取自 sub-app-kit 发布时记录的、与同版本 Shell 一致的版本表，不再读取 monorepo 中的 tenant-app。monorepo 内原有的 `pnpm create:sub-app` 继续可用。见[规格 · 前端脚手架](../spec.md#前端脚手架)。

**Blocked by:** 04 统一版本与发布流程

**Status:** ready-for-agent

- [ ] 生成命令接受目标目录参数，默认生成到当前目录下 `<应用编码>-sub-app`；目标已存在时失败
- [ ] 构建 sub-app-kit 时，从 tenant-app 生成共享依赖版本表并打进发布产物；生成器在仓库外读取该表，在 monorepo 内继续可用
- [ ] 生成的 `package.json` 依赖已发布的 `@forge/sdk`、`@forge/ui`、`@forge/sub-app-kit` 的同一版本，不使用 `workspace:` 协议
- [ ] 可执行文件以编译后的 JavaScript 发布，不依赖 Node 的 TypeScript 类型剥离
- [ ] 生成的工程带一个使用 `ListPage` 的列表页示例，保留独立开发宿主与 `forge-sub-app-lint`
- [ ] 更新 [docs/sub-app.md](../../../docs/sub-app.md)：仓库外的生成方式、App Registry 登记与套餐开放步骤
- [ ] 测试：扩展 sub-app-kit 现有的生成器测试，覆盖仓库外生成（临时目录、无 monorepo）时依赖版本取自版本表、不含 `workspace:`；monorepo 内生成行为不变
